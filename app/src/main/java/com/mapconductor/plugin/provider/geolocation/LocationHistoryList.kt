package com.mapconductor.plugin.provider.geolocation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 履歴表示：最新30件を単純にリスト表示（自動スクロールなし） */
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
