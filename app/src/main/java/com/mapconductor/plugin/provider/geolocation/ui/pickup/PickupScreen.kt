package com.mapconductor.plugin.provider.geolocation.ui.pickup

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation._core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation._core.data.room.LocationSample

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.text.Normalizer

@Composable
fun PickupScreen(
    vm: PickupViewModel = viewModel()
) {
    val input by vm.input.collectAsState()
    val scope = rememberCoroutineScope()

    // ▼ 反映ボタンを押すまでは表示しないゲート + スナップショット格納
    var hasApplied by remember { mutableStateOf(false) }
    var displayRows by remember { mutableStateOf<List<LocationSample>>(emptyList()) }
    var showShortageWarn by remember { mutableStateOf(false) }

    // -----------------------------
    // ▼ dataselector 導入（厳密案）
    // -----------------------------
    val app = LocalContext.current.applicationContext as Application
    val context = LocalContext.current

    // Dao に observeAll(): Flow<List<LocationSample>> がある前提
    val baseFlow: Flow<List<LocationSample>> = remember(context) {
        AppDatabase.get(context).locationSampleDao().observeAll()
    }

    val selectorVm: SelectorViewModel = viewModel(
        factory = SelectorViewModel.Factory(
            app = app,
            baseFlow = baseFlow,
            getMillis = { it.createdAt },   // ← あなたの LocationSample の時刻プロパティ
            getAccuracy = { it.accuracy }   // ← 無ければ { null }
        )
    )

    // ==== 反映ボタンから SelectorViewModel の条件へ反映するヘルパ ====
    val jst = remember { ZoneId.of("Asia/Tokyo") }
    fun todayStartMillis(): Long =
        LocalDate.now(jst).atStartOfDay(jst).toInstant().toEpochMilli()

    // H:mm[:ss] 形式も許容するフォーマッタ
    val timeFmt = remember {
        DateTimeFormatterBuilder()
            .appendValue(ChronoField.HOUR_OF_DAY, 1)  // 1 or 2 桁
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalStart()
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .optionalEnd()
            .toFormatter()
    }

    fun parseHmsToOffsetMillis(raw: String): Long? {
        // 全角→半角、前後空白除去
        val t = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        if (t.isEmpty()) return null
        // H:mm[:ss] を柔軟に許容
        val m = Regex("""^(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?$""").matchEntire(t) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (h !in 0..23 || min !in 0..59 || sec !in 0..59) return null
        val totalSec = h * 3600 + min * 60 + sec
        return totalSec * 1000L
    }

    // ★ ここを suspend に変更し、内部で直接 update する（外側で順序制御しやすくする）
    suspend fun applySelectorCondition() {
        val intervalMs = input.intervalSec.trim().toLongOrNull()
            ?.coerceIn(1, 86_400)
            ?.times(1000L)

        when (input.mode) {
            PickupMode.PERIOD -> {
                val base = todayStartMillis()
                val from = parseHmsToOffsetMillis(input.startHms)?.let { base + it }
                val to   = parseHmsToOffsetMillis(input.endHms)?.let { base + it }
                selectorVm.update { cond ->
                    cond.copy(
                        mode = com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition.Mode.ByPeriod,
                        fromMillis = from,
                        toMillis = to,
                        limit = null,
                        minAccuracyM = cond.minAccuracyM,
                        intervalMs = intervalMs
                    )
                }
            }
            PickupMode.COUNT -> {
                val limit = input.count.trim().toIntOrNull()?.coerceIn(1, 10_000)
                selectorVm.update { cond ->
                    cond.copy(
                        mode = com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition.Mode.ByCount,
                        fromMillis = null,
                        toMillis = null,
                        limit = limit,
                        minAccuracyM = cond.minAccuracyM,
                        intervalMs = intervalMs // 件数指定でも間隔を併用したい場合は残す
                    )
                }
            }
        }
    }

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
                    onClick = {
                        vm.updateMode(PickupMode.PERIOD)
                        hasApplied = false // ← 変更されたので未反映状態に戻す
                    },
                    label = { Text("期間指定") }
                )
                FilterChip(
                    selected = input.mode == PickupMode.COUNT,
                    onClick = {
                        vm.updateMode(PickupMode.COUNT)
                        hasApplied = false // ← 変更されたので未反映状態に戻す
                    },
                    label = { Text("件数指定") }
                )
            }
        }

        // 取得間隔
        OutlinedTextField(
            value = input.intervalSec,
            onValueChange = {
                vm.updateIntervalSec(it)
                hasApplied = false // ← 入力変更時は未反映
            },
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
                    onValueChange = {
                        vm.updateStartHms(it)
                        hasApplied = false // ← 入力変更時は未反映
                    },
                    label = { Text("開始 (HH:mm:ss)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = input.endHms,
                    onValueChange = {
                        vm.updateEndHms(it)
                        hasApplied = false // ← 入力変更時は未反映
                    },
                    label = { Text("終了 (HH:mm:ss)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            // 件数指定：入力のみ（実行は下部の「反映」に統一）
            OutlinedTextField(
                value = input.count,
                onValueChange = {
                    vm.updateCount(it)
                    hasApplied = false // ← 入力変更時は未反映
                },
                label = { Text("件数 (1..10000)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 実行行：反映のみ（反映時に selector 条件を同期 → スナップショット取得）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch {
                    // 1) 条件を保存（suspend化したので順序が担保される）
                    applySelectorCondition()
                    // 2) 1回だけ結果を取得して固定する
                    val snapshot = selectorVm.rows.first()
                    displayRows = snapshot
                    hasApplied = true
                }

                // 既存の reflect() ロジック（件数不足アラートなど）は従来通り
                vm.reflect()
                showShortageWarn = (vm.uiState.value as? PickupUiState.Done)?.shortage == true
            }) { Text("反映") }

            // クリアが必要なら ViewModel 側に API を追加
            // TextButton(onClick = { /* vm.clear(); also reset selector if needed */ }) { Text("クリア") }
        }

        // ▼▼▼ リスト描画は「反映済み」のときだけ & スナップショットを使う ▼▼▼
        if (hasApplied) {
            PickupListBySamples(samples = displayRows)
        } else {
            PickupListBySamples(samples = emptyList())
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
}

@Composable
private fun PickupListBySamples(samples: List<LocationSample>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(samples) { idx, sample ->
            PickupRowFromSample(index = idx + 1, sample = sample)
            Divider()
        }
    }
}

/* =============================
   表示行：LocationHistoryList と見た目を完全一致
   （Formatters のIFをそのまま使用）
   ============================= */
@Composable
private fun PickupRowFromSample(index: Int, sample: LocationSample?) {
    val time   = sample?.let { Formatters.timeJst(it.createdAt) } ?: "-"
    val prov   = sample?.let { Formatters.providerText(it.provider) } ?: "-"
    val bat    = sample?.let { Formatters.batteryText(it.batteryPct, it.isCharging) } ?: "-"
    val loc    = sample?.let { Formatters.latLonAcc(it.lat, it.lon, it.accuracy) } ?: "-"
    val head   = sample?.let { Formatters.headingText(it.headingDeg) } ?: "-"
    val course = sample?.let { Formatters.courseText(it.courseDeg) } ?: "-"
    val speed  = sample?.let { Formatters.speedText(it.speedMps) } ?: "-"
    val gnss   = sample?.let { Formatters.gnssUsedTotal(it.gnssUsed, it.gnssTotal) } ?: "-"
    val cn0    = sample?.let { Formatters.cn0Text(it.gnssCn0Mean) } ?: "-"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Time")
            Text(time, style = MaterialTheme.typography.bodyMedium)
        }

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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Location")
            Text(loc, style = MaterialTheme.typography.bodyMedium)
        }

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
