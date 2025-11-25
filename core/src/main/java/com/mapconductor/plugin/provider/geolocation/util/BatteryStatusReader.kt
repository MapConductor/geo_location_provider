package com.mapconductor.plugin.provider.geolocation.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

internal object BatteryStatusReader {
    data class Status(val percent: Int, val isCharging: Boolean)

    fun read(context: Context): Status {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // Prefer BATTERY_PROPERTY_CAPACITY; fall back to the broadcast when unknown or out of range
        var pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct <= 0 || pct > 100) {
            val intent = registerBatteryIntent(context)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            pct = if (level >= 0 && scale > 0) {
                ((level * 100f) / scale).toInt()
            } else {
                0
            }
        }

        // Charging state
        val statusIntent = registerBatteryIntent(context)
        val status = statusIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = statusIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        return Status(percent = pct.coerceIn(0, 100), isCharging = charging)
    }

    private fun registerBatteryIntent(context: Context): Intent? {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        return if (Build.VERSION.SDK_INT >= 33) {
            // API 33+ exported receivers must use the new overload
            context.registerReceiver(null, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(null, filter)
        }
    }
}

