package com.mapconductor.plugin.provider.geolocation.ui.pickup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample

// ▼ 見た目統一のための追加インポート
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.CompassCalibration
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight

@Composable
fun PickupScreen(
    vm: PickupViewModel = viewModel()
) {
    val input by vm.input.collectAsState()
    val uiState by vm.uiState.collectAsState()

    var showShortageWarn by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 方式選択
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("方式：", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = input.mode == PickupMode.PERIOD,
                    onClick = { vm.updateMode(PickupMode.PERIOD) },
                    label = { Text("期間指定") }
                )
                FilterChip(
                    selected = input.mode == PickupMode.COUNT,
                    onClick = { vm.updateMode(PickupMode.COUNT) },
                    label = { Text("件数指定") }
                )
            }
        }

        // 取得間隔
        OutlinedTextField(
            value = input.intervalSec,
            onValueChange = vm::updateIntervalSec,
            label = { Text("間隔（秒） 1..86,400") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (input.mode == PickupMode.PERIOD) {
            Text("対象は本日 00:00:00 ～ 現在（JST）に自動クリップ。開始・終了はグリッドに対して「含む」です。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input.startHms,
                    onValueChange = vm::updateStartHms,   // ← 正式名
                    label = { Text("開始 (HH:mm:ss)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = input.endHms,
                    onValueChange = vm::updateEndHms,     // ← 正式名
                    label = { Text("終了 (HH:mm:ss)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            // 件数指定：入力のみ（実行は下部の「反映」に統一）
            OutlinedTextField(
                value = input.count,
                onValueChange = vm::updateCount,
                label = { Text("件数 (1..10000)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 実行行：反映のみ（抽出は撤廃）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                vm.reflect()
                showShortageWarn = (vm.uiState.value as? PickupUiState.Done)?.shortage == true
            }) { Text("反映") }

            // クリア機能は ViewModel にAPIが無いため未表示（必要なら Idle へ戻す関数を追加してください）
            // TextButton(onClick = vm::clear) { Text("クリア") }
        }

        // 結果リスト
        val slots: List<PickupSlot> =
            when (val s = uiState) {
                is PickupUiState.Done -> s.slots
                else -> emptyList()
            }
        PickupList(slots = slots)

        if (showShortageWarn) {
            AlertDialog(
                onDismissRequest = { showShortageWarn = false },
                confirmButton = {
                    TextButton(onClick = { showShortageWarn = false }) { Text("OK") }
                },
                title = { Text("データが不足しています") },
                text = { Text("指定件数に満たないため、取得できた最大件数で表示しています。") }
            )
        }
    }
}

@Composable
private fun PickupList(slots: List<PickupSlot>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(slots) { idx, slot ->
            PickupRow(index = idx + 1, slot = slot)
            Divider()
        }
    }
}

/* =============================
   表示行：LocationHistoryList に見た目を完全一致
   （Formatters のIFをそのまま使用）
   ============================= */
@Composable
private fun PickupRow(index: Int, slot: PickupSlot) {
    val item: LocationSample? = slot.sample

    val time   = item?.let { Formatters.timeJst(it.createdAt) } ?: "-"
    val prov   = item?.let { Formatters.providerText(it.provider) } ?: "-"
    val bat    = item?.let { Formatters.batteryText(it.batteryPct, it.isCharging) } ?: "-"
    val loc    = item?.let { Formatters.latLonAcc(it.lat, it.lon, it.accuracy) } ?: "-"
    val head   = item?.let { Formatters.headingText(it.headingDeg) } ?: "-"
    val course = item?.let { Formatters.courseText(it.courseDeg) } ?: "-"
    val speed  = item?.let { Formatters.speedText(it.speedMps) } ?: "-"
    val gnss   = item?.let { Formatters.gnssUsedTotal(it.gnssUsed, it.gnssTotal) } ?: "-"
    val cn0    = item?.let { Formatters.cn0Text(it.gnssCn0Mean) } ?: "-"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1行目: [時計] Time
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Time")
            Text(time, style = MaterialTheme.typography.bodyMedium)
        }

        // 2行目: [アンテナ] Provider / [バッテリー] Battery
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SettingsInputAntenna, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Provider")
            Text(prov, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            Icon(Icons.Outlined.BatteryFull, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Battery")
            Text(bat, style = MaterialTheme.typography.bodyMedium)
        }

        // 3行目: [地球] Location
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Location")
            Text(loc, style = MaterialTheme.typography.bodyMedium)
        }

        // 4行目: [コンパス] Heading / [矢印] Course / [スピード] Speed
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CompassCalibration, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Heading")
            Text(head, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Course")
            Text(course, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Speed")
            Text(speed, style = MaterialTheme.typography.bodyMedium)
        }

        // 5行目: [衛星] GNSS / [電波] C/N0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SatelliteAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("GNSS")
            Text(gnss, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            Icon(Icons.Outlined.SignalCellularAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("C/N0")
            Text(cn0, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BoldLabel(label: String) {
    Text("$label : ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
}
