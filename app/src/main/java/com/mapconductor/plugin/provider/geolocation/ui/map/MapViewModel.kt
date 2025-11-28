package com.mapconductor.plugin.provider.geolocation.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
        private const val SOURCE_LIMIT = 1000
    }

    private val gpsEnabled = MutableStateFlow(false)
    private val drEnabled = MutableStateFlow(false)
    private val limitText = MutableStateFlow(DEFAULT_LIMIT.toString())
    private val appliedFilter = MutableStateFlow<Filter?>(null)
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
        val filterApplied: Boolean
    )

    val uiState: StateFlow<UiState> =
        combine(sourceFlow, gpsEnabled, drEnabled, limitText, appliedFilter) { samples, gps, dr, limitStr, filter ->
            val filterApplied = filter != null

            val (markers, latest) =
                if (filter == null) {
                    emptyList<LocationSample>() to null
                } else {
                    val filtered = samples.filter { s ->
                        val isGps = s.provider == "gps"
                        val isDr = s.provider == "dead_reckoning"
                        when {
                            filter.gps && !filter.dr -> isGps
                            !filter.gps && filter.dr -> isDr
                            filter.gps && filter.dr -> isGps || isDr
                            else -> false
                        }
                    }

                    val clipped = filtered.take(filter.limit)
                    clipped to clipped.firstOrNull()
                }

            UiState(
                gpsChecked = gps,
                drChecked = dr,
                limitText = limitStr,
                markers = markers,
                latest = latest,
                filterApplied = filterApplied
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
                filterApplied = false
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
}
