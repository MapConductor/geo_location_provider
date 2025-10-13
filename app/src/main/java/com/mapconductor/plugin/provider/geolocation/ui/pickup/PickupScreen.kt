package com.mapconductor.plugin.provider.geolocation.ui.pickup

import android.app.Application
import androidx.compose.foundation.clickable
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
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.material3.ExperimentalMaterial3Api

@Composable
fun PickupScreen() {
    val scope = rememberCoroutineScope()

    // -----------------------------
    // ▼ dataselector：DAO を渡して VM 構築
    // -----------------------------
    val app = LocalContext.current.applicationContext as Application
    val context = LocalContext.current
    val dao = remember(context) { AppDatabase.get(context).locationSampleDao() }
    val selectorVm: SelectorViewModel = viewModel(
        factory = SelectorViewModel.Factory(app = app, dao = dao)
    )

    // ====== 入力フィールド（統一フォーム） ======
    val jst = remember { ZoneId.of("Asia/Tokyo") }
    val dateFmt = remember { DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT) }
    val hmsFmt  = remember { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT) }

    // 既定値：今日の 00:00:00 ～ 現在
    val now = remember { ZonedDateTime.now(jst) }
    var fromDate by remember { mutableStateOf(now.toLocalDate().format(dateFmt)) }
    var fromHms  by remember { mutableStateOf("00:00:00") }
    var toDate   by remember { mutableStateOf(now.toLocalDate().format(dateFmt)) }
    var toHms    by remember { mutableStateOf(now.toLocalTime().format(hmsFmt)) }
    var sortOrder by remember { mutableStateOf(SortOrder.NewestFirst) }
    var countText by remember { mutableStateOf("100") }
    var intervalSecText by remember { mutableStateOf("3600") } // 0で無効

    // 反映後の固定表示用スナップショット
    var displayRows by remember { mutableStateOf<List<LocationSample>>(emptyList()) }

    // ====== 変換ユーティリティ ======
    fun parseDateMillis(text: String): Long? =
        runCatching { LocalDate.parse(text.trim(), dateFmt).atStartOfDay(jst).toInstant().toEpochMilli() }.getOrNull()

    fun parseHmsToMillisOfDay(raw: String): Long? {
        val t = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        val m = Regex("""^(\d{1,2}):(\d{1,2})(?::(\d{1,2}))?$""").matchEntire(t) ?: return null
        val hh = m.groupValues[1].toInt()
        val mm = m.groupValues[2].toInt()
        val ss = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (hh !in 0..23 || mm !in 0..59 || ss !in 0..59) return null
        return (hh * 3600 + mm * 60 + ss) * 1000L
    }

    fun combine(dateMs: Long?, hmsMs: Long?): Long? {
        if (dateMs == null) return null
        val base = Instant.ofEpochMilli(dateMs).atZone(jst).toLocalDate()
        val lt = hmsMs?.let { LocalTime.ofSecondOfDay(it / 1000) } ?: LocalTime.MIDNIGHT
        return base.atTime(lt).atZone(jst).toInstant().toEpochMilli()
    }

    fun clampToNow(toMs: Long?): Long? {
        val nowMs = System.currentTimeMillis()
        return toMs?.let { minOf(it, nowMs) }
    }

    // ====== 画面 ======
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pickup（統一）", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = fromDate, onValueChange = { fromDate = it },
                label = { Text("From 日付 (yyyy/MM/dd)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = fromHms, onValueChange = { fromHms = it },
                label = { Text("From 時刻 (HH:mm:ss)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = toDate, onValueChange = { toDate = it },
                label = { Text("To 日付 (yyyy/MM/dd)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = toHms, onValueChange = { toHms = it },
                label = { Text("To 時刻 (HH:mm:ss)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CountDirectionSelector(
                value = sortOrder,
                onChange = { sortOrder = it },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = countText, onValueChange = { countText = it },
                label = { Text("件数（上限）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        // 間隔（秒）
        OutlinedTextField(
            value = intervalSecText, onValueChange = { intervalSecText = it },
            label = { Text("間隔（秒）※0で無効") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        // 実行（反映）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch {
                    // 1) 入力→ミリ秒
                    val fromDateMs = parseDateMillis(fromDate)
                    val toDateMs = parseDateMillis(toDate)
                    val fromHmsMs = parseHmsToMillisOfDay(fromHms) ?: 0L
                    val toHmsMs = parseHmsToMillisOfDay(toHms) ?: 0L

                    var fromMs = combine(fromDateMs, fromHmsMs)
                    var toMs = combine(toDateMs, toHmsMs)
                    toMs = clampToNow(toMs)

                    // From>To は入れ替え（UI下流は必ず From<To）
                    if (fromMs != null && toMs != null && fromMs > toMs) {
                        val tmp = fromMs; fromMs = toMs; toMs = tmp
                    }

                    val limit = countText.trim().toIntOrNull()?.coerceIn(1, 100_000) ?: 100
                    val intervalSec = intervalSecText.trim().toIntOrNull()?.coerceIn(0, 86_400) ?: 0
                    val intervalMs = if (intervalSec > 0) intervalSec * 1000L else null

                    // 2) Prefs 更新（SelectorViewModel 内の Flow が反応）
                    selectorVm.update { cond ->
                        cond.copy(
                            // Mode は ByPeriod 固定（統一画面）
                            mode = SelectorCondition.Mode.ByPeriod,
                            fromMillis = fromMs,
                            toMillis = toMs,
                            limit = limit,
                            // 既存互換：ms と seconds のどちらでも動く（usecase 側で seconds 優先）
                            intervalMs = intervalMs,
                            // 参考で HMS も保存（下位では使わない）
                            fromHms = fromHms, toHms = toHms,
                            sortOrder = sortOrder
                        )
                    }

                    // 3) 一度だけ結果を固定表示
                    // 反映内の最後、displayRows = selectorVm.rows.first() の直後など
                    displayRows = when (sortOrder) {
                        SortOrder.NewestFirst -> displayRows.sortedByDescending { it.createdAt } // フィールド名は実体に合わせる
                        SortOrder.OldestFirst -> displayRows.sortedBy { it.createdAt }
                    }                }
            }) { Text("反映") }
        }

        // 結果
        PickupListBySamples(samples = displayRows)
    }
}

/* ====== リスト ====== */
@Composable
private fun PickupListBySamples(samples: List<LocationSample>) {
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(samples) { idx, sample ->
            PickupRowFromSample(index = idx + 1, sample = sample)
            Divider()
        }
    }
}

/* ====== 行表示（既存 Formatters に合わせ）====== */
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("Time"); Text(time, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SettingsInputAntenna, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("Provider"); Text(prov, style = MaterialTheme.typography.bodyMedium)
            Text(" / ", style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.BatteryFull, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("Battery"); Text(bat, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("Location"); Text(loc, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CompassCalibration, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("Heading"); Text(head, style = MaterialTheme.typography.bodyMedium)
            Text(" / ", style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("Course"); Text(course, style = MaterialTheme.typography.bodyMedium)
            Text(" / ", style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("Speed"); Text(speed, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SatelliteAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("GNSS"); Text(gnss, style = MaterialTheme.typography.bodyMedium)
            Text(" / ", style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.Outlined.SignalCellularAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); BoldLabel("C/N0"); Text(cn0, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable private fun BoldLabel(label: String) {
    Text("$label : ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountDirectionSelector(
    value: com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder,
    onChange: (com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (value == com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder.NewestFirst)
        "新しい方から優先" else "古い方から優先"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            label = { Text("件数の適用方向") },     // ← 要件どおり名称変更
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("新しい方から優先") },
                onClick = {
                    onChange(com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder.NewestFirst)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("古い方から優先") },
                onClick = {
                    onChange(com.mapconductor.plugin.provider.geolocation._dataselector.condition.SortOrder.OldestFirst)
                    expanded = false
                }
            )
        }
    }
}