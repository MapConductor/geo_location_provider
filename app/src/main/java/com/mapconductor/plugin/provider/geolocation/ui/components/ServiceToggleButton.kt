package com.mapconductor.plugin.provider.geolocation.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
        val running =
            runCatching {
                ServiceStateIndicator.isRunning(context, GeoLocationService::class.java)
            }.getOrElse { false }
        runningState.value = running
    }

    fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun startServiceForeground() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, GeoLocationService::class.java).apply {
                action = GeoLocationService.ACTION_START
            }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val locOk = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notifOk = if (Build.VERSION.SDK_INT >= 33) {
            granted[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }

        if (locOk && notifOk) {
            startServiceForeground()
        } else if (!locOk) {
            Toast.makeText(context, "Location permission is required.", Toast.LENGTH_SHORT).show()
        }

        scope.launch {
            try {
                delay(500)
                refreshState()
            } finally {
                busyState.value = false
            }
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

    TextButton(
        onClick = {
            if (busyState.value) return@TextButton
            busyState.value = true

            val currentlyRunning = runningState.value

            if (currentlyRunning) {
                // Request stop
                context.startService(
                    Intent(context, GeoLocationService::class.java).apply {
                        action = GeoLocationService.ACTION_STOP
                    }
                )
                scope.launch {
                    try {
                        delay(500)
                        refreshState()
                    } finally {
                        busyState.value = false
                    }
                }
                return@TextButton
            }

            val need = mutableListOf<String>()
            if (!hasLocationPermission()) {
                need += Manifest.permission.ACCESS_FINE_LOCATION
                need += Manifest.permission.ACCESS_COARSE_LOCATION
            }
            if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= 33) {
                need += Manifest.permission.POST_NOTIFICATIONS
            }

            if (need.isEmpty()) {
                startServiceForeground()
                scope.launch {
                    try {
                        delay(500)
                        refreshState()
                    } finally {
                        busyState.value = false
                    }
                }
            } else {
                permLauncher.launch(need.toTypedArray())
            }
        },
        enabled = !busyState.value
    ) {
        Text(
            text = if (runningState.value) "Stop" else "Start",
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
