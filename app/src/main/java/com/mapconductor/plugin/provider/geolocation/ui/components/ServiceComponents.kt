package com.mapconductor.plugin.provider.geolocation.ui.components

// ★ 既存3ファイルで使っていた import を “そのまま” まとめて持ってきてください。
// 例:
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import com.mapconductor.plugin.provider.geolocation.ui.history.HistoryViewModel
import com.mapconductor.plugin.provider.geolocation.ui.main.ManualExportViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ...他、既存の3ファイルの import を必要分ペースト...

/* =========================
   以下に既存3つの Composable を
   “そのまま” コピペで同居させます
   ========================= */

// 1) ServiceLocationReadout（既存ファイルから本体を丸ごと貼り付け）
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

// 2) LocationHistoryList（既存ファイルから本体を丸ごと貼り付け）
@Composable
fun LocationHistoryList(
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = viewModel()
) {
    val items by vm.latest30.collectAsState(initial = emptyList())
    val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(
            items = items,
            key = { it.id }
        ) { s ->
            val time = timeFmt.format(Date(s.createdAt))
            Text(
                text = buildString {
                    append("time: "); append(time); append('\n')
                    append("lat: "); append("%.6f".format(s.lat)); append('\n')
                    append("lon: "); append("%.6f".format(s.lon)); append('\n')
                    append("acc: "); append("%.1f m".format(s.accuracy)); append('\n')
                    s.provider?.let { append("provider: "); append(it); append('\n') }
                    append("battery: "); append("${s.batteryPct}%")
                    if (s.isCharging) append(" (charging)")
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
            Divider(thickness = 0.5.dp)
        }
    }
}

// 3) ExportButton（既存ファイルから本体を丸ごと貼り付け）
@Composable
fun ExportButton(
    modifier: Modifier = Modifier,
    limit: Int? = 1000 // null なら全件
) {
    val context = LocalContext.current
    val vm: ManualExportViewModel = viewModel(factory = ManualExportViewModel.factory(context))

    Button(onClick = { vm.exportAll(limit) }, modifier = modifier) {
        Text("Export to Downloads")
    }
}
