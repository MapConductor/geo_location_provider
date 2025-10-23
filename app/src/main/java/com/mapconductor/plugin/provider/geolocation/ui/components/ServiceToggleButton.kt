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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun ServiceToggleAction() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val runningState = remember { mutableStateOf(false) }
    val busyState = remember { mutableStateOf(false) } // 二重タップ防止
    val scope = remember { CoroutineScope(Dispatchers.Main + Job()) }

    // 初回・復帰時に“実態”を問い合わせ（保存しない）
    fun refreshState() {
        scope.launch {
            busyState.value = true
            val running = ServiceStateIndicator.isRunning(context, GeoLocationService::class.java)
            runningState.value = running
            busyState.value = false
        }
    }

    LaunchedEffect(Unit) { refreshState() }

    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) refreshState()
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
                // 開始：前景サービスとして開始（5秒以内に startForeground 必須）
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GeoLocationService::class.java).apply {
                        action = GeoLocationService.ACTION_START
                    }
                )
            }

            // 少し待ってから再プローブ（Stop/Start の実反映を確認）
            scope.launch {
                delay(500) // 端末差に合わせ 400–1000ms で調整
                refreshState()
            }
        },
        enabled = !busyState.value
    ) {
        Text(if (runningState.value) "Stop" else "Start")
    }
}
