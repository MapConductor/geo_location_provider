package com.mapconductor.plugin.provider.geolocation.ui.components

import android.content.Intent
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import com.mapconductor.plugin.provider.geolocation.util.ServiceStateIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ServiceToggleAction() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // true = service running (show Stop) / false = stopped (show Start)
    val runningState = remember { mutableStateOf(false) }

    // Prevent double taps while a request is in flight
    val busyState = remember { mutableStateOf(false) }

    // Scope used for one-shot actions tied to this composable
    val scope = rememberCoroutineScope()

    suspend fun refreshState() {
        val running = ServiceStateIndicator.isRunning(
            context,
            GeoLocationService::class.java
        )
        runningState.value = running
    }

    // Periodically poll service state
    LaunchedEffect(Unit) {
        while (true) {
            refreshState()
            delay(1_000L)
        }
    }

    // Refresh state when the screen returns to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                scope.launch { refreshState() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    IconButton(
        onClick = {
            if (busyState.value) return@IconButton
            busyState.value = true

            // Optimistically flip UI state, then correct after service responds
            val currentlyRunning = runningState.value
            val targetRunning = !currentlyRunning
            runningState.value = targetRunning

            if (currentlyRunning) {
                // Request stop
                context.startService(
                    Intent(context, GeoLocationService::class.java).apply {
                        action = GeoLocationService.ACTION_STOP
                    }
                )
            } else {
                // Request start as foreground service
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GeoLocationService::class.java).apply {
                        action = GeoLocationService.ACTION_START
                    }
                )
            }

            // Wait a bit and then re-query the real state
            scope.launch {
                delay(500) // Increase to 700-1000ms if needed
                refreshState()
                busyState.value = false
            }
        },
        enabled = !busyState.value
    ) {
        Text(if (runningState.value) "Stop" else "Start")
    }
}
