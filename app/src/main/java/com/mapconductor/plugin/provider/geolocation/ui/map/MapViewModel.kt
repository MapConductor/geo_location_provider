package com.mapconductor.plugin.provider.geolocation.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters
import com.mapconductor.plugin.provider.geolocation.ui.common.ProviderKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val limitText = MutableStateFlow(DEFAULT_LIMIT.toString())
    private val appliedFilter = MutableStateFlow<Filter?>(null)
    private val mapSession = MutableStateFlow(0)
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
        val limitText: String,
        val markers: List<LocationSample>,
        val latest: LocationSample?,
        val filterApplied: Boolean,
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
        combine(sourceFlow, gpsEnabled, drEnabled, limitText, appliedFilter) {
                samples,
                gps,
                dr,
                limitStr,
                filter ->
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
                    s.coerceIn(0.3, 1.0)
                }

            UiState(
                gpsChecked = gps,
                drChecked = dr,
                limitText = limitStr,
                markers = markers,
                latest = latest,
                filterApplied = filterApplied,
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
                limitText = DEFAULT_LIMIT.toString(),
                markers = emptyList(),
                latest = null,
                filterApplied = false,
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
            kotlin.math.sin(dLat / 2.0) * kotlin.math.sin(dLat / 2.0) +
                kotlin.math.cos(rLat1) * kotlin.math.cos(rLat2) *
                kotlin.math.sin(dLon / 2.0) * kotlin.math.sin(dLon / 2.0)
        val c = 2.0 * kotlin.math.asin(kotlin.math.sqrt(a))
        return r * c
    }
}
