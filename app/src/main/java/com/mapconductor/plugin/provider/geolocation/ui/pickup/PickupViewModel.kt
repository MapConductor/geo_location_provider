package com.mapconductor.plugin.provider.geolocation.ui.pickup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class PickupMode { PERIOD, COUNT }

data class PickupInput(
    val mode: PickupMode = PickupMode.PERIOD,
    val intervalSec: String = "5",
    val startHms: String = "00:00:00",
    val endHms: String = "23:59:59",
    val count: String = "100"
)

sealed class PickupUiState {
    data object Idle : PickupUiState()
    data object Loading : PickupUiState()
    data class Error(val message: String) : PickupUiState()
    data class Done(val summary: String, val items: List<LocationSample>) : PickupUiState()
}

class PickupViewModel(app: Application) : AndroidViewModel(app) {
    private val zoneId: ZoneId = ZoneId.of("Asia/Tokyo")
    private val dao = AppDatabase.get(app).locationSampleDao()

    private val _input = MutableStateFlow(PickupInput())
    val input: StateFlow<PickupInput> = _input

    private val _uiState = MutableStateFlow<PickupUiState>(PickupUiState.Idle)
    val uiState: StateFlow<PickupUiState> = _uiState

    fun updateMode(mode: PickupMode) = _input.update { it.copy(mode = mode) }
    fun updateIntervalSec(text: String) = _input.update { it.copy(intervalSec = text) }
    fun updateStartHms(text: String) = _input.update { it.copy(startHms = text) }
    fun updateEndHms(text: String) = _input.update { it.copy(endHms = text) }
    fun updateCount(text: String) = _input.update { it.copy(count = text) }

    fun reflect() {
        val cur = _input.value
        val intervalSec = cur.intervalSec.toIntOrNull()
        if (intervalSec == null || intervalSec !in 1..86_400) {
            _uiState.value = PickupUiState.Error("間隔（秒）は 1..86,400 の整数で入力してください。")
            return
        }
        when (cur.mode) {
            PickupMode.PERIOD -> reflectPeriod(intervalSec, cur.startHms, cur.endHms)
            PickupMode.COUNT  -> reflectCount(intervalSec, cur.count)
        }
    }

    private fun reflectPeriod(intervalSec: Int, startHms: String, endHms: String) {
        val (okStart, startMs) = parseHmsToTodayMillis(startHms)
        val (okEnd, endMs) = parseHmsToTodayMillis(endHms)
        if (!okStart || !okEnd) {
            _uiState.value = PickupUiState.Error("時刻は hh:mm:ss 形式で入力してください。")
            return
        }
        val todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()
        val start = max(todayStart, startMs)
        val end = min(nowMs, endMs)
        if (start > end) {
            _uiState.value = PickupUiState.Error("時刻範囲が不正です（開始＞終了）。")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PickupUiState.Loading

            // DAO は [from, to) なので end+1 を渡して「終了を含める」動きに寄せる
            val raw = dao.findBetween(start, end + 1)
            val picked = downsampleByInterval(raw, intervalSec * 1000L) { it.createdAt }

            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            val startStr = Instant.ofEpochMilli(start).atZone(zoneId).format(fmt)
            val endStr = Instant.ofEpochMilli(end).atZone(zoneId).format(fmt)
            val summary = "方式:期間指定 / 間隔:${intervalSec}s / 範囲:$startStr ~ $endStr / 件数:${picked.size}"

            withContext(Dispatchers.Main) {
                _uiState.value = PickupUiState.Done(summary, picked)
            }
        }
    }

    private fun reflectCount(intervalSec: Int, countText: String) {
        val wanted = countText.toIntOrNull()
        if (wanted == null || wanted !in 1..20_000) {
            _uiState.value = PickupUiState.Error("件数は 1..20,000 の整数で入力してください。")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PickupUiState.Loading

            val todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val nowMs = System.currentTimeMillis()

            // 当日全件（当日限定なので初版は素直に取得）
            val allToday = dao.findBetween(todayStart, nowMs + 1)
            val down = downsampleByInterval(allToday, intervalSec * 1000L) { it.createdAt }

            val actual = if (down.size <= wanted) down else down.takeLast(wanted)

            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            val sumStr = "方式:件数指定 / 間隔:${intervalSec}s / 目標:${wanted}件 / 実際:${actual.size}件 / 範囲:" +
                    if (actual.isNotEmpty()) {
                        val s = Instant.ofEpochMilli(actual.first().createdAt).atZone(zoneId).format(fmt)
                        val e = Instant.ofEpochMilli(actual.last().createdAt).atZone(zoneId).format(fmt)
                        "$s ~ $e"
                    } else {
                        "該当なし"
                    }

            withContext(Dispatchers.Main) {
                _uiState.value = PickupUiState.Done(sumStr, actual)
            }
        }
    }

    private fun parseHmsToTodayMillis(hms: String): Pair<Boolean, Long> {
        val parts = hms.split(":")
        if (parts.size != 3) return false to 0L
        val h = parts[0].toIntOrNull() ?: return false to 0L
        val m = parts[1].toIntOrNull() ?: return false to 0L
        val s = parts[2].toIntOrNull() ?: return false to 0L
        if (h !in 0..23 || m !in 0..59 || s !in 0..59) return false to 0L

        val today = LocalDate.now(zoneId)
        val zdt = ZonedDateTime.of(today.year, today.monthValue, today.dayOfMonth, h, m, s, 0, zoneId)
        return true to zdt.toInstant().toEpochMilli()
    }

    /** 昇順配列に対して「前回採用 + intervalMs 以上」を採用する間引き */
    private inline fun <T> downsampleByInterval(
        list: List<T>,
        intervalMs: Long,
        crossinline ts: (T) -> Long
    ): List<T> {
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<T>(list.size)
        var nextAllowed = Long.MIN_VALUE
        for (e in list) {
            val t = ts(e)
            if (t >= nextAllowed) {
                out.add(e)
                nextAllowed = t + intervalMs
            }
        }
        return out
    }

    private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
        this.value = block(this.value)
    }
}
