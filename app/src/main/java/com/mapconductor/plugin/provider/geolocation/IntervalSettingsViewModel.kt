package com.mapconductor.plugin.provider.geolocation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 更新間隔(秒) 設定用 VM：初期値= Binder > DataStore > 5秒 */
class IntervalSettingsViewModel(private val appContext: Context) : ViewModel() {

    private val settingsStore = SettingsStore(appContext)

    // UI バインド（文字列で保持）
    private val _secondsText = MutableStateFlow("5")
    val secondsText: StateFlow<String> = _secondsText

    // Service バインド用
    private var bound = false
    private var binder: GeoLocationService.LocalBinder? = null
    private var connectJob: Job? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            (service as? GeoLocationService.LocalBinder)?.let { lb ->
                binder = lb
                bound = true
                // 取得できたら即 UI に反映（Binder が最優先）
                val ms = lb.getService().getUpdateIntervalMs()
                val sec = (ms / 1000L).coerceAtLeast(5)
                _secondsText.value = sec.toString()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            binder = null
        }
    }

    init {
        // ① Binder 最優先で初期化を試みる
        connectJob = viewModelScope.launch(Dispatchers.Main) {
            val intent = Intent(appContext, GeoLocationService::class.java)
            // 既に動作していれば onServiceConnected が呼ばれる
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)

            // ② Binder が来ない場合のフォールバック（DataStore→5秒）
            withContext(Dispatchers.IO) {
                val saved = settingsStore.updateIntervalMsFlow.first()
                if (!bound) {
                    val sec = ((saved ?: 5_000L) / 1000L).coerceAtLeast(5)
                    _secondsText.value = sec.toString()
                }
            }
        }
    }

    /** ユーザ入力変更（秒） */
    fun onSecondsChanged(text: String) {
        _secondsText.value = text
    }

    /** 保存＋Service反映（ms） */
    fun saveAndApply() {
        viewModelScope.launch(Dispatchers.IO) {
            val sec = _secondsText.value.toLongOrNull()
            val clampedSec = when {
                sec == null -> 5L
                sec < 5L    -> 5L
                else        -> sec
            }
            val ms = clampedSec * 1000L

            // DataStore 保存
            settingsStore.setUpdateIntervalMs(ms)

            // Service へ反映（ACTION_UPDATE_INTERVAL）
            withContext(Dispatchers.Main) {
                val intent = Intent(appContext, GeoLocationService::class.java).apply {
                    action = GeoLocationService.ACTION_UPDATE_INTERVAL
                    putExtra(GeoLocationService.EXTRA_UPDATE_MS, ms)
                }
                ContextCompat.startForegroundService(appContext, intent)
                Toast.makeText(appContext, "更新間隔を ${clampedSec}秒 に設定しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (bound) {
            appContext.unbindService(conn)
            bound = false
        }
        connectJob?.cancel()
    }
}
