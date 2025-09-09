package com.mapconductor.plugin.provider.geolocation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 画面の状態（必要に応じて項目を足してください） */
data class UiState(
    val title: String = "GeoLocationProvider",
    val subtitle: String = "FGS + Location + Battery + Room",
)

/** 一回性イベント（Toast など） */
sealed interface UiEvent {
    data class ShowToast(val message: String) : UiEvent
}

/** 画面用 ViewModel（最小構成） */
class GeoLocationProviderViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    private val vmScope = CoroutineScope(Dispatchers.Default)

    fun onGeoLocationProviderClicked() {
        vmScope.launch { _events.emit(UiEvent.ShowToast("GeoLocationProvider button clicked!")) }
        _uiState.update { it.copy() }
    }
}
