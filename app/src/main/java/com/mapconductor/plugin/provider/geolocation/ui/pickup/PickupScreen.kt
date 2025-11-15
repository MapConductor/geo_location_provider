package com.mapconductor.plugin.provider.geolocation.ui.pickup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation.condition.SortOrder
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters.LoggingList
import com.mapconductor.plugin.provider.geolocation.usecase.BuildSelectedSlots
import com.mapconductor.plugin.provider.geolocation.usecase.SelectorUseCases
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PickupScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // -----------------------------
    // ▼ dataselector：UseCase をファクトリから取得
    //   （Pickup は DB/DAO を知らない）
    // -----------------------------
    val buildSelectedSlots: BuildSelectedSlots = remember {
        SelectorUseCases.buildSelectedSlots(context)
    }

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

    // 反映後の固定表示用スナップショット（SelectedSlot = 欠測も行として保持）
    var displaySlots by remember { mutableStateOf<List<SelectedSlot>>(emptyList()) }

    // ====== 変換ユーティリティ ======
    fun parseDateMillis(text: String): Long? =
        runCatching {
            LocalDate.parse(text.trim(), dateFmt)
                .atStartOfDay(jst)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()

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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = fromDate,
                onValueChange = { fromDate = it },
                label = { Text("From 日付 (yyyy/MM/dd)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = fromHms,
                onValueChange = { fromHms = it },
                label = { Text("From 時刻 (HH:mm:ss)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = toDate,
                onValueChange = { toDate = it },
                label = { Text("To 日付 (yyyy/MM/dd)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = toHms,
                onValueChange = { toHms = it },
                label = { Text("To 時刻 (HH:mm:ss)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
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
                value = countText,
                onValueChange = { countText = it },
                label = { Text("件数（上限）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        // 間隔（秒）
        OutlinedTextField(
            value = intervalSecText,
            onValueChange = { intervalSecText = it },
            label = { Text("間隔（秒）※0で無効") },
            singleLine = true,
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

                    // From>To は入れ替え
                    if (fromMs != null && toMs != null && fromMs > toMs) {
                        val tmp = fromMs
                        fromMs = toMs
                        toMs = tmp
                    }

                    val limit = countText.trim().toIntOrNull()?.coerceIn(1, 100_000) ?: 100
                    val intervalSec = intervalSecText.trim().toIntOrNull()?.coerceIn(0, 86_400) ?: 0
                    val interval = if (intervalSec > 0) intervalSec.toLong() else null

                    // 2) UseCase 実行（欠測は SelectedSlot.sample == null）
                    val slots = buildSelectedSlots(
                        SelectorCondition(
                            fromMillis = fromMs,
                            toMillis = toMs,
                            intervalSec = interval,
                            limit = limit,
                            minAccuracy = null,
                            order = sortOrder
                        )
                    )

                    // 3) 結果を固定表示
                    displaySlots = slots
                }
            }) {
                Text("反映")
            }
        }

        // 結果
        PickupListBySlots(slots = displaySlots)
    }
}

/* ====== リスト（SelectedSlot = 欠測も含む） ====== */
@Composable
private fun PickupListBySlots(slots: List<SelectedSlot>) {
    // 実データ付きスロットだけを表示対象にする
    val filledSlots = remember(slots) { slots.filter { it.sample != null } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(filledSlots) { index, slot ->
            if (index == 0) {
                HorizontalDivider()
            }

            LoggingList(slot)
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountDirectionSelector(
    value: SortOrder,
    onChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (value == SortOrder.NewestFirst) "新しい方から優先" else "古い方から優先"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            label = { Text("件数の適用方向") },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("新しい方から優先") },
                onClick = {
                    onChange(SortOrder.NewestFirst)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("古い方から優先") },
                onClick = {
                    onChange(SortOrder.OldestFirst)
                    expanded = false
                }
            )
        }
    }
}
