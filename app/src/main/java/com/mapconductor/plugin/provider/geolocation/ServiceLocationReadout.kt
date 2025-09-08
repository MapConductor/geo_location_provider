package com.mapconductor.plugin.provider.geolocation

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.location.Location
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext

@Composable
fun ServiceLocationReadout() {
    val context = LocalContext.current
    var service by remember { mutableStateOf<GeoLocationService?>(null) }

    // ※ 権限取得後/サービス開始後にバインドするのが安全
    DisposableEffect(Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as GeoLocationService.LocalBinder).getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        // 既に Start ボタンで起動する前提なら flags=0 でもOK
        context.bindService(Intent(context, GeoLocationService::class.java), conn, 0)
        onDispose { runCatching { context.unbindService(conn) } }
    }

    val loc by (service?.locationFlow?.collectAsState(initial = null)
        ?: remember { mutableStateOf<Location?>(null) })

    val batt by (service?.batteryFlow?.collectAsState(initial = null)
        ?: remember { mutableStateOf<GeoLocationService.BatteryInfo?>(null) })

    Text(
        text = buildString {
            if (loc != null) {
                append("lat=%.6f\nlon=%.6f\nacc=%.1fm".format(loc!!.latitude, loc!!.longitude, loc!!.accuracy))
            } else {
                append("Waiting for location…")
            }
            batt?.let {
                append("\n")
                append("battery=${it.pct}%")
                if (it.isCharging) append(" (充電中)")
            }
        }
    )
}
