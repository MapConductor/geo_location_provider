package com.mapconductor.plugin.provider.geolocation.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Simple UI state for the main screen. */
data class UiState(
    val title: String = "GeoLocationProvider",
    val subtitle: String = "FGS + Location + Battery + Room",
)

/** One-shot events (for example Toast). */
sealed interface UiEvent {
    data class ShowToast(val message: String) : UiEvent
}

/** Minimal ViewModel for the main GeoLocationProvider screen. */
class GeoLocationProviderViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    fun onGeoLocationProviderClicked() {
        viewModelScope.launch {
            _events.emit(UiEvent.ShowToast("GeoLocationProvider button clicked"))
        }
        _uiState.update { it.copy() }
    }
}
