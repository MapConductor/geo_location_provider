package com.mapconductor.plugin.provider.geolocation.ui.main

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mapconductor.plugin.provider.geolocation._core.service.GeoLocationService

object ServiceLauncher {
    fun startLocationService(context: Context) {
        val intent = Intent(context, GeoLocationService::class.java).apply {
            action = "ACTION_START_LOCATION"
        }
        // ★ 必ず startForegroundService
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopLocationService(context: Context) {
        val intent = Intent(context, GeoLocationService::class.java).apply {
            action = "ACTION_STOP_LOCATION"
        }
        context.startService(intent)
    }
}
