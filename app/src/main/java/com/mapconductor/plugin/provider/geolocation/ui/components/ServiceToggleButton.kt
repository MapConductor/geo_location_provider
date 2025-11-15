package com.mapconductor.plugin.provider.geolocation.ui.components

import android.content.Intent
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import com.mapconductor.plugin.provider.geolocation.util.ServiceStateIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ServiceToggleAction() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // true = 動作中 → Stop 表示 / false = 停止中 → Start 表示
    val runningState = remember { mutableStateOf(false) }

    // 二重タップ防止用
    val busyState = remember { mutableStateOf(false) }

    val scope = remember { CoroutineScope(Dispatchers.Main + Job()) }

    // ★ 状態問い合わせ中でもボタンは有効のままにする
    //   → 初期状態からグレーにならないようにする
    fun refreshState() {
        scope.launch {
            val running = ServiceStateIndicator.isRunning(
                context,
                GeoLocationService::class.java
            )
            runningState.value = running
        }
    }

    // 初回に一度だけ実態を問い合わせる
    LaunchedEffect(Unit) {
        refreshState()
    }

    // 画面復帰時にも状態を取り直す
    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
    }

    IconButton(
        onClick = {
            if (busyState.value) return@IconButton
            busyState.value = true

            if (runningState.value) {
                // 停止：onStartCommand(ACTION_STOP) に確実に届く経路
                context.startService(
                    Intent(context, GeoLocationService::class.java).apply {
                        action = GeoLocationService.ACTION_STOP
                    }
                )
            } else {
                // 開始：前景サービスとして開始
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GeoLocationService::class.java).apply {
                        action = GeoLocationService.ACTION_START
                    }
                )
            }

            // 少し待ってから再プローブ（Stop/Start の実反映を確認）
            scope.launch {
                delay(500) // 端末差に合わせて必要なら調整
                refreshState()
                busyState.value = false
            }
        },
        enabled = !busyState.value
    ) {
        Text(if (runningState.value) "Stop" else "Start")
    }
}
