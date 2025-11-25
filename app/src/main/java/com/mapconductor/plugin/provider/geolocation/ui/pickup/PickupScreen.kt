package com.mapconductor.plugin.provider.geolocation.ui.pickup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PickupScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // UseCase from dataselector; Pickup does not know DB/DAO types directly
    val buildSelectedSlots: BuildSelectedSlots = remember {
        SelectorUseCases.buildSelectedSlots(context)
    }

    // Input fields
    val jst = remember { ZoneId.of("Asia/Tokyo") }
    val dateFmt = remember { DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT) }
    val hmsFmt = remember { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT) }

    // Defaults: today 00:00:00 to now
    val now = remember { ZonedDateTime.now(jst) }
    var fromDate by remember { mutableStateOf(now.toLocalDate().format(dateFmt)) }
    var fromHms by remember { mutableStateOf("00:00:00") }
    var toDate by remember { mutableStateOf(now.toLocalDate().format(dateFmt)) }
    var toHms by remember { mutableStateOf(now.toLocalTime().format(hmsFmt)) }
    var sortOrder by remember { mutableStateOf(SortOrder.NewestFirst) }
    var countText by remember { mutableStateOf("100") }
    var intervalSecText by remember { mutableStateOf("3600") } // 0 means disabled

    // Snapshot to display after apply (SelectedSlot, including gaps)
    var displaySlots by remember { mutableStateOf<List<SelectedSlot>>(emptyList()) }

    // Conversion utilities
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
        return toMs?.coerceAtMost(nowMs)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Input fields
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = fromDate,
                onValueChange = { fromDate = it },
                label = { Text("From date (yyyy/MM/dd)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = fromHms,
                onValueChange = { fromHms = it },
                label = { Text("From time (HH:mm:ss)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = toDate,
                onValueChange = { toDate = it },
                label = { Text("To date (yyyy/MM/dd)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = toHms,
                onValueChange = { toHms = it },
                label = { Text("To time (HH:mm:ss)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CountDirectionSelector(
                value = sortOrder,
                onChange = { sortOrder = it },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = countText,
                onValueChange = { countText = it },
                label = { Text("Count limit") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = intervalSecText,
            onValueChange = { intervalSecText = it },
            label = { Text("Interval (sec, 0 to disable)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                scope.launch {
                    // 1) Convert input to millis
                    val fromDateMs = parseDateMillis(fromDate)
                    val toDateMs = parseDateMillis(toDate)
                    val fromHmsMs = parseHmsToMillisOfDay(fromHms) ?: 0L
                    val toHmsMs = parseHmsToMillisOfDay(toHms) ?: 0L

                    var fromMs = combine(fromDateMs, fromHmsMs)
                    var toMs = combine(toDateMs, toHmsMs)
                    toMs = clampToNow(toMs)

                    // Swap if From > To
                    if (fromMs != null && toMs != null && fromMs > toMs) {
                        val tmp = fromMs
                        fromMs = toMs
                        toMs = tmp
                    }

                    val limit = countText.trim().toIntOrNull()?.coerceIn(1, 100_000) ?: 100
                    val intervalSec =
                        intervalSecText.trim().toIntOrNull()?.coerceIn(0, 86_400) ?: 0
                    val interval = if (intervalSec > 0) intervalSec.toLong() else null

                    // 2) Execute use case (gaps are represented as SelectedSlot.sample == null)
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

                    // 3) Fix display snapshot
                    displaySlots = slots
                }
            }) {
                Text("Apply")
            }
        }

        PickupListBySlots(slots = displaySlots)
    }
}

// List (SelectedSlot, including gaps).
@Composable
private fun PickupListBySlots(slots: List<SelectedSlot>) {
    // Filter out empty slots for display
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

@Composable
private fun CountDirectionSelector(
    value: SortOrder,
    onChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text("Count direction")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onChange(SortOrder.NewestFirst) }
            ) {
                Text("Newest first")
            }
            TextButton(
                onClick = { onChange(SortOrder.OldestFirst) }
            ) {
                Text("Oldest first")
            }
        }
    }
}
