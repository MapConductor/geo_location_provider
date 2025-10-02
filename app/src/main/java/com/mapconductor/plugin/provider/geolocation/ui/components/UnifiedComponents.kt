package com.mapconductor.plugin.provider.geolocation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf   // ★ 追加
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.ui.history.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LocationHistoryList(
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = viewModel()
) {
    val rows = vm.latest.collectAsState(initial = emptyList()).value
    val baseFmt = remember { SimpleDateFormat("yyyy/MM/dd(EEE) HH:mm:ss", Locale.JAPAN) }

    // ★ 初回に測ったアイテム高さ(px)を保持（可変LIMITを使う場合の計測用）
    val itemHeightPxState = remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(items = rows, key = { it.id }) { s ->
            // --- 新：2行フォーマット ---
            val hundredths = ((s.createdAt % 1000L) / 10L).toInt()
            val timeStr = "${baseFmt.format(Date(s.createdAt))}.%02d".format(hundredths)
            val provider = s.provider ?: "-"
            val charge = if (s.isCharging) "充電中" else "非充電"

            val line1 = "Time : $timeStr / Provider : $provider / Battery : ${s.batteryPct}%($charge)"
            val line2 = "Location : [Lon]%.6f, [Lat]%.6f, [Acc]%.2fm"
                .format(s.lon, s.lat, s.accuracy)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .onGloballyPositioned { coords ->
                        // 可変LIMITのための実アイテム高さ計測（初回のみ）
                        if (itemHeightPxState.value == null) {
                            itemHeightPxState.value = coords.size.height
                        }
                    }
            ) {
                Text(line1, style = MaterialTheme.typography.bodySmall) // ★ 修正：typography
                Text(line2, style = MaterialTheme.typography.bodySmall) // ★ 修正：typography
            }
            Divider(thickness = 0.5.dp)
        }
    }
}
