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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * TopAppBar/Scaffold は外側（AppRoot）で提供する前提。
 * 本画面は余白を作らないため、コンテンツのみを描画します。
 */
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
        // 方式ラジオ
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = input.mode == PickupMode.PERIOD,
                onClick = { vm.updateMode(PickupMode.PERIOD) }
            )
            Text("期間指定")
            Spacer(Modifier.width(16.dp))
            RadioButton(
                selected = input.mode == PickupMode.COUNT,
                onClick = { vm.updateMode(PickupMode.COUNT) }
            )
            Text("件数指定")
        }

        // 共通：間隔
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
                    onValueChange = vm::updateStartHms,
                    label = { Text("開始 hh:mm:ss") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = input.endHms,
                    onValueChange = vm::updateEndHms,
                    label = { Text("終了 hh:mm:ss") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            OutlinedTextField(
                value = input.count,
                onValueChange = vm::updateCount,
                label = { Text("件数 1..20,000（不足時は最大件数を表示）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("探索対象：本日 00:00:00 ～ 現在（JST）。now 以下で最大の理想時刻から過去へ N スロット。")
        }

        // 実行ボタン
        val enabled = when (input.mode) {
            PickupMode.PERIOD -> input.intervalSec.isNotBlank() && input.startHms.isNotBlank() && input.endHms.isNotBlank()
            PickupMode.COUNT  -> input.intervalSec.isNotBlank() && input.count.isNotBlank()
        }
        Button(
            onClick = { vm.reflect() },
            enabled = enabled,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Reflection!")
        }

        // 結果表示
        when (val s = uiState) {
            is PickupUiState.Idle -> {}
            is PickupUiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is PickupUiState.Error -> Text(text = s.message, color = MaterialTheme.colorScheme.error)
            is PickupUiState.Done -> {
                // 件数不足アラート（件数指定モード時のみ有効）
                LaunchedEffect(s.shortage) {
                    if (s.shortage) showShortageWarn = true
                }

                Text(s.summary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                PickupList(slots = s.slots)
            }
        }
    }

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

@Composable
private fun PickupList(slots: List<PickupSlot>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(slots) { idx, slot ->
            PickupRow(index = idx + 1, slot = slot)
            HorizontalDivider()
        }
    }
}

/** スロット行（sample == null の時は全項目 "-"） */
@Composable
private fun PickupRow(index: Int, slot: PickupSlot) {
    val sample = slot.sample
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (sample == null) {
            Text("No.$index  Time : -  / Battery : -")
            Text("Location : -  Acc : -")
            Text("Heading : - / Course : - / Speed : -")
            Text("GNSS : -  / C/N0 : -")
        } else {
            Text("No.$index  Time : ${sample.formattedTimeOrDash()}  / Battery : ${sample.formattedBatteryOrDash()}")
            Text("Location : ${sample.formattedLatLngOrDash()}  Acc : ${sample.formattedAccOrDash()}")
            Text("Heading : ${sample.formattedHeadingOrDash()} / Course : ${sample.formattedCourseOrDash()} / Speed : ${sample.formattedSpeedOrDash()}")
            Text("GNSS : ${sample.formattedGnssOrDash()}  / C/N0 : ${sample.formattedCn0OrDash()}")
        }
    }
}

/* =============================
   表示用拡張（リフレクション版）
   —— プロパティ名の差異に強い実装。
   固定できるなら直接プロパティ参照に置換してください。
   ============================= */

private val jst: ZoneId = ZoneId.of("Asia/Tokyo")
private val timeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)

private fun LocationSample.formattedTimeOrDash(): String {
    val ts = getNumberField("createdAt", "timestampMillis")?.toLong() ?: return "-"
    return try { Instant.ofEpochMilli(ts).atZone(jst).format(timeFmt) } catch (_: Exception) { "-" }
}

private fun LocationSample.formattedBatteryOrDash(): String {
    val pct = getNumberField("batteryPct", "batteryPercent", "battery")?.toInt() ?: return "-"
    return "$pct%"
}

private fun LocationSample.formattedLatLngOrDash(): String {
    val lat = getNumberField("latitude", "lat")?.toDouble()
    val lon = getNumberField("longitude", "lng", "lon")?.toDouble()
    return if (lat != null && lon != null) {
        "%,.6f, %,.6f".format(Locale.US, lat, lon)
    } else "-"
}

private fun LocationSample.formattedAccOrDash(): String {
    val acc = getNumberField("accuracy", "accuracyMeters", "acc")?.toDouble() ?: return "-"
    return "%,.2fm".format(Locale.US, acc)
}

private fun LocationSample.formattedHeadingOrDash(): String {
    val v = getNumberField("headingDeg", "bearingDeg", "heading")?.toDouble() ?: return "-"
    return "%,.1f°".format(Locale.US, v)
}

private fun LocationSample.formattedCourseOrDash(): String {
    val v = getNumberField("courseDeg", "course")?.toDouble() ?: return "-"
    return "%,.1f°".format(Locale.US, v)
}

private fun LocationSample.formattedSpeedOrDash(): String {
    val kmh = getNumberField("speedKmh")?.toDouble()
    val mps = getNumberField("speedMps")?.toDouble()
    val k = kmh ?: (mps?.let { it * 3.6 })
    val m = mps ?: (kmh?.let { it / 3.6 })
    return if (k != null && m != null) {
        "%,.1fKm/h(%,.1fm/s)".format(Locale.US, k, m)
    } else "-"
}

private fun LocationSample.formattedGnssOrDash(): String {
    val used = getNumberField("gnssUsed", "satellitesUsed")?.toInt()
    val tot  = getNumberField("gnssTotal", "satellitesTotal")?.toInt()
    return if (used != null && tot != null) "$used/$tot" else "-"
}

private fun LocationSample.formattedCn0OrDash(): String {
    val v = getNumberField("cn0DbHz", "cn0")?.toDouble() ?: return "-"
    return "%,.1fdB-Hz".format(Locale.US, v)
}

/** 指定名の最初に見つかった数値プロパティを返す（Number / String数値 に対応） */
private fun Any.getNumberField(vararg names: String): Number? {
    for (n in names) {
        val v = runCatching {
            val f = this.javaClass.getDeclaredField(n).apply { isAccessible = true }
            f.get(this)
        }.getOrNull() ?: continue
        when (v) {
            is Number -> return v
            is String -> v.toDoubleOrNull()?.let { return it }
        }
    }
    return null
}
