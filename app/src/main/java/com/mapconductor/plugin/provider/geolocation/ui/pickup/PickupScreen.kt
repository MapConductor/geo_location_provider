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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickupScreen(
    onBack: () -> Unit,
    vm: PickupViewModel = viewModel()
) {
    val input by vm.input.collectAsState()
    val uiState by vm.uiState.collectAsState()

    var showShortageWarn by remember { mutableStateOf(false) } // 必要なら不足警告用

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pickup") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(16.dp),
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
                Text("対象は本日 00:00:00 ～ 現在（JST）に自動クリップされます。")
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
                    label = { Text("件数 1..20,000（不足時は最大件数）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("探索対象：本日 00:00:00 ～ 現在（JST）")
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
                    Text(s.summary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    PickupList(items = s.items)
                }
            }
        }

        // 件数不足のワーニングを出すならここで制御（必要時だけ表示）
        if (showShortageWarn) {
            AlertDialog(
                onDismissRequest = { showShortageWarn = false },
                confirmButton = {
                    TextButton(onClick = { showShortageWarn = false }) { Text("OK") }
                },
                title = { Text("件数が不足しています") },
                text = { Text("指定件数に満たないため、最大件数で表示します。") }
            )
        }
    }
}

@Composable
private fun PickupList(items: List<LocationSample>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(items) { idx, item ->
            PickupRow(index = idx + 1, item = item)
            HorizontalDivider()
        }
    }
}

/** 既存の行フォーマットがあればそちらを流用。ここでは簡易版（拡張関数で安全に文字列化）。 */
@Composable
private fun PickupRow(index: Int, item: LocationSample) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("No.$index  Time : ${item.formattedTimeOrDash()}  / Battery : ${item.formattedBatteryOrDash()}")
        Text("Location : ${item.formattedLatLngOrDash()}  Acc : ${item.formattedAccOrDash()}")
        Text("Heading : ${item.formattedHeadingOrDash()} / Course : ${item.formattedCourseOrDash()} / Speed : ${item.formattedSpeedOrDash()}")
        Text("GNSS : ${item.formattedGnssOrDash()}  / C/N0 : ${item.formattedCn0OrDash()}")
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
