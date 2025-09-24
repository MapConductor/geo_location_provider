package com.mapconductor.plugin.provider.geolocation.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.ui.history.HistoryViewModel
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService

@Composable
fun ServiceLocationReadout(
    historyVm: HistoryViewModel = viewModel()
) {
    // サービスの実行状態だけは StateFlow で参照（バインド不要）
    val running by GeoLocationService.running.collectAsState()

    // 位置/バッテリーは DB の最新1件から読む（ほぼリアルタイム）
    val latest by historyVm.latestOne.collectAsState(initial = null)

    val text = buildString {
        if (latest != null) {
            append("lat=%.6f\nlon=%.6f\nacc=%.1fm".format(
                latest!!.lat, latest!!.lon, latest!!.accuracy
            ))
        } else {
            append(if (running) "Initializing location…" else "Service stopped")
        }
        append("\n")
        if (latest != null) {
            append("battery=${latest!!.batteryPct}%")
            if (latest!!.isCharging) append(" (charging)")
        } else {
            append(if (running) "battery=initializing…" else "battery=unknown")
        }
    }

    Text(text)
}