package com.mapconductor.plugin.provider.geolocation.ui.components

import android.content.Intent
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    // true = service running (show Stop) / false = stopped (show Start)
    val runningState = remember { mutableStateOf(false) }

    // Prevent double taps while a request is in flight
    val busyState = remember { mutableStateOf(false) }

    // Scope used for periodic state checks
    val scope = remember { CoroutineScope(Dispatchers.Main + Job()) }

    fun refreshState() {
        scope.launch {
            val running = ServiceStateIndicator.isRunning(
                context,
                GeoLocationService::class.java
            )
            runningState.value = running
        }
    }

    // Periodically poll service state
    LaunchedEffect(Unit) {
        while (true) {
            refreshState()
            delay(1_000L)
        }
    }

    // Refresh state when the screen returns to foreground
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

