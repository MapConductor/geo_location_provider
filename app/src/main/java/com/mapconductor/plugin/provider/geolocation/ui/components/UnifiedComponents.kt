package com.mapconductor.plugin.provider.geolocation.ui.components

// --- 必要インポート（不足しがちなものを明示で揃える） ---
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
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

// ★ Compose のプロパティ委譲（by 句）を使わない方針にすると型推論エラーを避けられます。
//   （どうしても使うなら `import androidx.compose.runtime.getValue` が必須）

/* =========================
   1) ServiceLocationReadout
   ========================= */
@Composable
fun ServiceLocationReadout(
    historyVm: HistoryViewModel = viewModel()
) {
    // サービス実行状態：StateFlow<Boolean> → State<Boolean> → Boolean へ
    val running = GeoLocationService.running.collectAsState().value

    // 最新データ：latestOne の型が非 null の場合は initial をダミーに変更してください
    val latest = historyVm.latestOne.collectAsState(initial = null).value
    // ↑ latestOne が Flow<YourEntity> (非 null) の場合は：
    // val latest = historyVm.latestOne.collectAsState(initial = historyVm.placeholder).value
    // のように “初期オブジェクト” を用意する

    val text = buildString {
        if (latest != null) {
            // latest の型が nullable 想定。非 null 型なら `?.` ではなく `.` でOK
            append("lat=%.6f\nlon=%.6f\nacc=%.1fm".format(
                latest.lat, latest.lon, latest.accuracy
            ))
        } else {
            append(if (running) "Initializing location…" else "Service stopped")
        }
        append("\n")
        if (latest != null) {
            append("battery=${latest.batteryPct}%")
            if (latest.isCharging) append(" (charging)")
        } else {
            append(if (running) "battery=initializing…" else "battery=unknown")
        }
    }

    Text(text)
}

/* =========================
   2) LocationHistoryList
   ========================= */
@Composable
fun LocationHistoryList(
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = viewModel()
) {
    val rows = vm.latest30.collectAsState(initial = emptyList()).value
    val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // rows の要素型が推論できないケースを避けるため items(...) を素直に使用
        items(
            items = rows,
            key = { it.id } // ← エンティティに id がある前提。無い場合は index を使う
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

/* =========================
   3) ExportButton
   ========================= */
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
