package com.mapconductor.plugin.provider.geolocation.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters
import com.mapconductor.plugin.provider.geolocation.ui.common.ProviderKind
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class MapCurveMode {
    LINEAR,
    BEZIER,
    SPLINE,
    CORNER_CUTTING_1,
    CORNER_CUTTING_2,
    CORNER_CUTTING_3
}

enum class MapPointSelectionMode {
    TIME_PRIORITY,
    DISTANCE_PRIORITY
}

/**
 * ViewModel for the map screen.
 *
 * - Observes latest LocationSample rows from DB.
 * - Applies provider filters (GPS / dead_reckoning) and display limit.
 * - Exposes the resulting list for marker rendering and the latest point for camera centering.
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val DEFAULT_LIMIT = 100
        private const val SOURCE_LIMIT = 5000
    }

    private val gpsEnabled = MutableStateFlow(false)
    private val drEnabled = MutableStateFlow(false)
    private val gpsDrEnabled = MutableStateFlow(false)
    private val curveMode = MutableStateFlow(MapCurveMode.LINEAR)
    private val pointSelectionMode =
        MutableStateFlow(MapPointSelectionMode.TIME_PRIORITY)
    private val limitText = MutableStateFlow(DEFAULT_LIMIT.toString())
    private val appliedFilter = MutableStateFlow<Filter?>(null)
    private val mapSession = MutableStateFlow(0)
    private var lastFollowedDrTimeMillis: Long? = null
    private val eventsFlow = MutableSharedFlow<Event>()

    private val sourceFlow = StorageService.latestFlow(
        app.applicationContext,
        limit = SOURCE_LIMIT
    )

    private data class Filter(
        val gps: Boolean,
        val dr: Boolean,
        val limit: Int
    )

    sealed class Event {
        data class ShowToast(val message: String) : Event()
    }

    data class UiState(
        val gpsChecked: Boolean,
        val drChecked: Boolean,
        val gpsDrChecked: Boolean,
        val limitText: String,
        val markers: List<LocationSample>,
        val gpsDrPath: List<LocationSample>,
        val latest: LocationSample?,
        val filterApplied: Boolean,
        val curveMode: MapCurveMode,
        val pointSelectionMode: MapPointSelectionMode,
        val displayedGpsCount: Int,
        val displayedDrCount: Int,
        val displayedTotalCount: Int,
        val dbGpsCount: Int,
        val dbDrCount: Int,
        val dbTotalCount: Int,
        // Debug information for the map overlay.
        val debugIsStatic: Boolean,
        val debugDrToGpsDistanceM: Double?,
        val debugLatestGpsAccuracyM: Float?,
        val debugLatestGpsSpeedMps: Double?,
        val debugLatestDrSpeedMps: Double?,
        val debugGpsInfluenceScale: Double?
    )

    val uiState: StateFlow<UiState> =
        combine(
            sourceFlow,
            gpsEnabled,
            drEnabled,
            gpsDrEnabled,
            limitText,
            appliedFilter,
            curveMode,
            pointSelectionMode
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val samples = values[0] as List<LocationSample>
            val gps = values[1] as Boolean
            val dr = values[2] as Boolean
            val gpsDr = values[3] as Boolean
            val limitStr = values[4] as String
            val filter = values[5] as Filter?
            val currentCurveMode = values[6] as MapCurveMode
            val currentPointSelectionMode = values[7] as MapPointSelectionMode

            val filterApplied = filter != null

            val normalized = samples.map { sample ->
                sample to Formatters.providerKind(sample.provider)
            }

            val dbGpsCount = normalized.count { (_, kind) -> kind == ProviderKind.Gps }
            val dbDrCount = normalized.count { (_, kind) -> kind == ProviderKind.DeadReckoning }
            val dbTotalCount = samples.size

            val markers: List<LocationSample>
            val latest: LocationSample?

            if (filter == null) {
                markers = emptyList()
                latest = null
            } else {
                val filtered = normalized
                    .filter { (_, kind) ->
                        val isGps = kind == ProviderKind.Gps
                        val isDr = kind == ProviderKind.DeadReckoning
                        when {
                            filter.gps && !filter.dr -> isGps
                            !filter.gps && filter.dr -> isDr
                            filter.gps && filter.dr -> isGps || isDr
                            else -> false
                        }
                    }
                    .map { it.first }

                val clipped = filtered.take(filter.limit)
                markers = clipped
                latest = clipped.firstOrNull()
            }

            val gpsDrPath: List<LocationSample> =
                if (filter != null && gpsDr) {
                    val candidates = normalized
                        .filter { (_, kind) ->
                            kind == ProviderKind.Gps ||
                                kind == ProviderKind.DeadReckoning
                        }
                        .map { it.first }
                        .take(filter.limit)

                    val hasGps = candidates.any { sample ->
                        Formatters.providerKind(sample.provider) == ProviderKind.Gps
                    }
                    val hasDr = candidates.any { sample ->
                        Formatters.providerKind(sample.provider) ==
                            ProviderKind.DeadReckoning
                    }

                    if (!hasGps || !hasDr) {
                        emptyList()
                    } else {
                        when (currentPointSelectionMode) {
                            MapPointSelectionMode.TIME_PRIORITY ->
                                buildTimePriorityPath(candidates)
                            MapPointSelectionMode.DISTANCE_PRIORITY ->
                                buildDistancePriorityPath(candidates)
                        }
                    }
                } else {
                    emptyList()
                }

            val displayedGpsCount = markers.count {
                Formatters.providerKind(it.provider) == ProviderKind.Gps
            }
            val displayedDrCount = markers.count {
                Formatters.providerKind(it.provider) == ProviderKind.DeadReckoning
            }
            val displayedTotalCount = markers.size

            // Debug: latest GPS / DR samples.
            val latestGps = normalized.firstOrNull { (_, kind) ->
                kind == ProviderKind.Gps
            }?.first
            val latestDr = normalized.firstOrNull { (_, kind) ->
                kind == ProviderKind.DeadReckoning
            }?.first

            val drToGpsDistanceM: Double? =
                if (latestGps != null && latestDr != null) {
                    distanceMeters(
                        latestGps.lat,
                        latestGps.lon,
                        latestDr.lat,
                        latestDr.lon
                    )
                } else {
                    null
                }

            // Read engine-side static flag from DrDebugState so that the
            // overlay reflects the internal DeadReckoning isLikelyStatic()
            // state rather than a separate GPS-only heuristic.
            val debugIsStatic: Boolean =
                com.mapconductor.plugin.provider.geolocation.debug.DrDebugState
                    .snapshot
                    .value
                    .isStatic

            val latestGpsAccuracy = latestGps?.accuracy
            val latestGpsSpeedMps = latestGps?.speedMps
            val latestDrSpeedMps = latestDr?.speedMps

            val gpsInfluenceScale: Double? =
                latestGpsAccuracy?.takeIf { it > 0f && !it.isNaN() }?.let { acc ->
                    val s = 10.0 / acc.toDouble()
                    s.coerceIn(0.1, 1.0)
                }

            // When a DR-only filter is applied, follow the latest DR point by
            // bumping the map session whenever a new DR sample arrives.
            if (filter != null && filter.dr && !filter.gps) {
                val latestDrTime = latestDr?.timeMillis
                if (latestDrTime != null) {
                    val prev = lastFollowedDrTimeMillis
                    if (prev == null) {
                        lastFollowedDrTimeMillis = latestDrTime
                    } else if (latestDrTime != prev) {
                        lastFollowedDrTimeMillis = latestDrTime
                        mapSession.value = mapSession.value + 1
                    }
                }
            } else {
                lastFollowedDrTimeMillis = null
            }

            UiState(
                gpsChecked = gps,
                drChecked = dr,
                gpsDrChecked = gpsDr,
                limitText = limitStr,
                markers = markers,
                gpsDrPath = gpsDrPath,
                latest = latest,
                filterApplied = filterApplied,
                 curveMode = currentCurveMode,
                 pointSelectionMode = currentPointSelectionMode,
                displayedGpsCount = displayedGpsCount,
                displayedDrCount = displayedDrCount,
                displayedTotalCount = displayedTotalCount,
                dbGpsCount = dbGpsCount,
                dbDrCount = dbDrCount,
                dbTotalCount = dbTotalCount,
                debugIsStatic = debugIsStatic,
                debugDrToGpsDistanceM = drToGpsDistanceM,
                debugLatestGpsAccuracyM = latestGpsAccuracy,
                debugLatestGpsSpeedMps = latestGpsSpeedMps,
                debugLatestDrSpeedMps = latestDrSpeedMps,
                debugGpsInfluenceScale = gpsInfluenceScale
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            UiState(
                gpsChecked = false,
                drChecked = false,
                gpsDrChecked = false,
                limitText = DEFAULT_LIMIT.toString(),
                markers = emptyList(),
                gpsDrPath = emptyList(),
                latest = null,
                filterApplied = false,
                curveMode = MapCurveMode.LINEAR,
                pointSelectionMode = MapPointSelectionMode.TIME_PRIORITY,
                displayedGpsCount = 0,
                displayedDrCount = 0,
                displayedTotalCount = 0,
                dbGpsCount = 0,
                dbDrCount = 0,
                dbTotalCount = 0,
                debugIsStatic = false,
                debugDrToGpsDistanceM = null,
                debugLatestGpsAccuracyM = null,
                debugLatestGpsSpeedMps = null,
                debugLatestDrSpeedMps = null,
                debugGpsInfluenceScale = null
            )
        )

    fun onGpsCheckedChange(checked: Boolean) {
        gpsEnabled.value = checked
    }

    fun onDrCheckedChange(checked: Boolean) {
        drEnabled.value = checked
    }

    fun onGpsDrCheckedChange(checked: Boolean) {
        gpsDrEnabled.value = checked
    }

    fun onCurveModeChange(mode: MapCurveMode) {
        curveMode.value = mode
    }

    fun onPointSelectionModeChange(mode: MapPointSelectionMode) {
        pointSelectionMode.value = mode
    }

    fun onLimitChanged(text: String) {
        if (text.isEmpty() || text.all { it.isDigit() }) {
            limitText.value = text
        }
    }

    val events: SharedFlow<Event> = eventsFlow
    val mapSessionState: StateFlow<Int> = mapSession

    fun onApplyClicked() {
        val currentFilter = appliedFilter.value
        if (currentFilter != null) {
            // Cancel: clear filter and return to editable state.
            appliedFilter.value = null
            lastFollowedDrTimeMillis = null
            return
        }

        if (!gpsEnabled.value && !drEnabled.value && !gpsDrEnabled.value) {
            viewModelScope.launch {
                eventsFlow.emit(Event.ShowToast("At least one checkbox must be selected."))
            }
            return
        }

        val raw = limitText.value
        val parsed = raw.toIntOrNull()
        val (limit, message) =
            when {
                parsed == null -> DEFAULT_LIMIT to "Count must be a number between 1 and $SOURCE_LIMIT."
                parsed < 1 -> 1 to "Count must be at least 1."
                parsed > SOURCE_LIMIT -> SOURCE_LIMIT to "Count must not exceed $SOURCE_LIMIT."
                else -> parsed to null
            }

        limitText.value = limit.toString()
        appliedFilter.value = Filter(
            gps = gpsEnabled.value,
            dr = drEnabled.value,
            limit = limit
        )
        mapSession.value = mapSession.value + 1

        if (message != null) {
            viewModelScope.launch {
                eventsFlow.emit(Event.ShowToast(message))
            }
        }
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(rLat1) * cos(rLat2) *
                sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * asin(sqrt(a))
        return r * c
    }

    private fun buildTimePriorityPath(
        samples: List<LocationSample>
    ): List<LocationSample> {
        return samples.sortedWith(
            compareBy<LocationSample> { it.timeMillis }
                .thenBy { providerSortKey(it.provider) }
        )
    }

    private fun buildDistancePriorityPath(
        samples: List<LocationSample>
    ): List<LocationSample> {
        if (samples.isEmpty()) {
            return emptyList()
        }

        val remaining = samples.toMutableList()
        remaining.sortBy { it.timeMillis }

        val path = mutableListOf<LocationSample>()
        var current = remaining.removeAt(0)
        path.add(current)

        var avgStepMeters: Double? = null

        while (remaining.isNotEmpty()) {
            var bestIndex = -1
            var bestDistance = Double.MAX_VALUE

            for (i in remaining.indices) {
                val candidate = remaining[i]
                val d = distanceMeters(
                    current.lat,
                    current.lon,
                    candidate.lat,
                    candidate.lon
                )
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = i
                }
            }

            if (bestIndex < 0) {
                break
            }

            val thresholdExceeded =
                if (avgStepMeters != null) {
                    val threshold = (avgStepMeters * 4.0).coerceAtLeast(50.0)
                    bestDistance > threshold
                } else {
                    false
                }

            if (thresholdExceeded) {
                remaining.removeAt(bestIndex)
                continue
            }

            val next = remaining.removeAt(bestIndex)
            path.add(next)

            avgStepMeters =
                if (avgStepMeters == null) {
                    bestDistance
                } else {
                    avgStepMeters * 0.8 + bestDistance * 0.2
                }

            current = next
        }

        return path
    }

    private fun providerSortKey(provider: String): Int {
        return when (Formatters.providerKind(provider)) {
            ProviderKind.Gps -> 0
            ProviderKind.DeadReckoning -> 1
            else -> 2
        }
    }
}
