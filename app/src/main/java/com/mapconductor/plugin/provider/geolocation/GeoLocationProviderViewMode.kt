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

// 画面の状態（View専用データ）
data class UiState(
    val buttonText: String = "GeoLocationProvider",
)

// 一回性イベント（Toast/ダイアログ表示合図など）
sealed interface UiEvent {
    data class ShowToast(val message: String) : UiEvent
}

class GeoLocationProviderViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    private val ioScope = CoroutineScope(Dispatchers.Default)

    /** ビジネスロジック：ボタンクリック時の処理 */
    fun onGeoLocationProviderClicked() {
        // ここで将来的に位置情報取得などのユースケースを呼び出す想定
        // 今回は簡易にイベントを発火
        ioScope.launch {
            _events.emit(UiEvent.ShowToast("GeoLocationProvider button clicked!"))
        }

        // UI状態を更新したい場合の例（今回は見た目変化なし）
        _uiState.update { it.copy() }
    }
}
