package com.mapconductor.plugin.provider.geolocation.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.mapconductor.plugin.provider.geolocation.ui.history.HistoryRow
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
            HistoryRow(item = s)
            HorizontalDivider(thickness = 0.5.dp, color = DividerDefaults.color)
        }
    }
}
