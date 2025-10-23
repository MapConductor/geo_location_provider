package com.mapconductor.plugin.provider.geolocation.ui.pickup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.room.LocationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 方式：期間指定 or 件数指定 */
enum class PickupMode { PERIOD, COUNT }

/** 画面入力モデル */
data class PickupInput(
    val mode: PickupMode = PickupMode.PERIOD,
    val intervalSec: String = "3600",       // T（秒）
    // PERIOD
    val startHms: String = "00:00:00",
    val endHms: String = "03:00:00",
    // COUNT
    val count: String = "10"                // 1..20,000
)

/** スロット（理想時刻 g と、それに最も近いサンプル or 欠測） */
data class PickupSlot(
    val idealMs: Long,
    val sample: LocationSample?,            // null の場合は欠測（UIは全項目 "-" 表示）
    val deltaMs: Long?                      // sample がある時のみ ts - ideal（デバッグ/将来表示用）
)

/** UI状態 */
sealed class PickupUiState {
    data object Idle : PickupUiState()
    data object Loading : PickupUiState()
    data class Error(val message: String) : PickupUiState()
    data class Done(
        val summary: String,
        val slots: List<PickupSlot>,
        val shortage: Boolean                // 件数指定で「ヒットが目標未満」だったか
    ) : PickupUiState()
}

class PickupViewModel(app: Application) : AndroidViewModel(app) {

    private val zoneId: ZoneId = ZoneId.of("Asia/Tokyo")
    private val dao = AppDatabase.get(app).locationSampleDao()

    private val _input = MutableStateFlow(PickupInput())
    val input: StateFlow<PickupInput> = _input

    private val _uiState = MutableStateFlow<PickupUiState>(PickupUiState.Idle)
    val uiState: StateFlow<PickupUiState> = _uiState

    // ---- 入力更新 ----
    fun updateMode(mode: PickupMode) = _input.update { it.copy(mode = mode) }
    fun updateIntervalSec(text: String) = _input.update { it.copy(intervalSec = text) }
    fun updateStartHms(text: String) = _input.update { it.copy(startHms = text) }
    fun updateEndHms(text: String) = _input.update { it.copy(endHms = text) }
    fun updateCount(text: String) = _input.update { it.copy(count = text) }

    /** 反映（抽出） */
    fun reflect() {
        val cur = _input.value
        val intervalSec = cur.intervalSec.toLongOrNull()
        if (intervalSec == null || intervalSec !in 1..86_400) {
            _uiState.value = PickupUiState.Error("間隔（秒）は 1..86,400 の整数で入力してください。")
            return
        }
        when (cur.mode) {
            PickupMode.PERIOD -> reflectPeriod(intervalSec, cur.startHms, cur.endHms)
            PickupMode.COUNT  -> reflectCount(intervalSec, cur.count)
        }
    }

    // ---- 期間指定 ----
    private fun reflectPeriod(intervalSec: Long, startHms: String, endHms: String) {
        val (okS, startMsRaw) = parseHmsToTodayMillis(startHms)
        val (okE, endMsRaw) = parseHmsToTodayMillis(endHms)
        if (!okS || !okE) {
            _uiState.value = PickupUiState.Error(
                "時刻は HH:mm / HH:mm:ss / HH:mm:ss.SSS のいずれかで入力してください。"
            )
            return
        }
        val todayStart = todayStartMs()
        val nowMs = System.currentTimeMillis()
        val start = max(todayStart, startMsRaw)
        val end = min(nowMs, endMsRaw)
        if (start > end) {
            _uiState.value = PickupUiState.Error("時刻範囲が不正です（開始＞終了）。")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PickupUiState.Loading

            val T = intervalSec * 1000L
            val W = T / 2L                              // 探索幅 ±(T/2) 両端含む
            val midnight = todayStart                   // JST 0:00 をアンカー

            // グリッド（開始・終了を「含む」）
            val grid = buildGridTimesInclusive(midnight, start, end, T)

            // DB取得範囲： [start - W, end + W] を本日範囲にクリップして半開区間へ
            val fromQuery = max(todayStart, start - W)
            val toQueryInclusive = min(nowMs, end + W)
            val records = dao.findBetween(fromQuery, toQueryInclusive + 1) // [from, to]

            val slots = snapToGrid(records, grid, W) { it.createdAt }
            val hits = slots.count { it.sample != null }

            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            val startStr = Instant.ofEpochMilli(start).atZone(zoneId).format(fmt)
            val endStr   = Instant.ofEpochMilli(end).atZone(zoneId).format(fmt)
            val summary = "方式:期間指定 / T=${intervalSec}s / W=±${W/1000}s / スロット=${grid.size} / ヒット=$hits / 範囲=$startStr ~ $endStr"

            withContext(Dispatchers.Main) {
                _uiState.value = PickupUiState.Done(summary, slots, shortage = false)
            }
        }
    }

    // ---- 件数指定（上限 20,000） ----
    private fun reflectCount(intervalSec: Long, countText: String) {
        val wanted = countText.toIntOrNull()
        if (wanted == null || wanted !in 1..20_000) {
            _uiState.value = PickupUiState.Error("件数は 1..20,000 の整数で入力してください。")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = PickupUiState.Loading

            val T = intervalSec * 1000L
            val W = T / 2L
            val todayStart = todayStartMs()
            val nowMs = System.currentTimeMillis()
            val midnight = todayStart

            // g0 = floor((now - midnight)/T)*T + midnight（now 以下で最大の理想時刻）
            val k0 = ((nowMs - midnight) / T)
            val g0 = midnight + k0 * T

            // 過去方向に N スロット生成（本日 0:00 を下限・含む）
            val gridDesc = ArrayList<Long>(wanted)
            var g = g0
            while (gridDesc.size < wanted && g >= midnight) {
                gridDesc.add(g)
                g -= T
            }
            // 昇順にして UI 表示順へ
            val grid = gridDesc.asReversed()

            // DB取得範囲： [minGrid - W, now + W] を本日範囲にクリップ
            val minG = if (grid.isNotEmpty()) grid.first() else g0
            val fromQuery = max(todayStart, minG - W)
            val toQueryInclusive = min(nowMs, nowMs + W) // +W しても nowMs を上限に
            val records = dao.findBetween(fromQuery, toQueryInclusive + 1)

            val slots = snapToGrid(records, grid, W) { it.createdAt }
            val hits = slots.count { it.sample != null }
            val shortage = hits < wanted

            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
            val sumStr = buildString {
                append("方式:件数指定 / T=${intervalSec}s / W=±${W/1000}s / 目標=$wanted / 実ヒット=$hits / 範囲:")
                if (grid.isNotEmpty()) {
                    val s = Instant.ofEpochMilli(grid.first()).atZone(zoneId).format(fmt)
                    val e = Instant.ofEpochMilli(grid.last()).atZone(zoneId).format(fmt)
                    append("$s ~ $e")
                } else {
                    append("該当なし")
                }
            }

            withContext(Dispatchers.Main) {
                _uiState.value = PickupUiState.Done(sumStr, slots, shortage = shortage)
            }
        }
    }

    // ======================
    // ユーティリティ
    // ======================

    /** 当日 00:00:00 (JST) の epoch millis */
    private fun todayStartMs(): Long =
        LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant().toEpochMilli()

    /** "HH:mm[:ss[.SSS]]" を今日(JST)の同時刻の epochMillis に変換。 */
    private fun parseHmsToTodayMillis(hms: String): Pair<Boolean, Long> {
        val t = hms.trim()
        if (t.isEmpty()) return false to 0L

        // 許容フォーマット：HH:mm / HH:mm:ss / HH:mm:ss.S / .SS / .SSS
        val formats = listOf(
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm:ss.S"),
            DateTimeFormatter.ofPattern("H:mm:ss.SS"),
            DateTimeFormatter.ofPattern("H:mm:ss.SSS")
        )

        val localTime: LocalTime = formats.firstNotNullOfOrNull { fmt ->
            runCatching { LocalTime.parse(t, fmt) }.getOrNull()
        } ?: return false to 0L

        val today = LocalDate.now(zoneId)
        val zdt = ZonedDateTime.of(
            today.year, today.monthValue, today.dayOfMonth,
            localTime.hour, localTime.minute, localTime.second, localTime.nano, zoneId
        )
        return true to zdt.toInstant().toEpochMilli()
    }

    /**
     * 期間指定：開始・終了**含む**でグリッド生成。
     * アンカーは JST 0:00、g_k = midnight + k*T
     */
    private fun buildGridTimesInclusive(
        midnight: Long,
        startMs: Long,
        endMs: Long,
        T: Long
    ): List<Long> {
        if (endMs < startMs) return emptyList()
        // ceil((start-midnight)/T)
        val firstK = ((startMs - midnight) + (T - 1)) / T
        // floor((end-midnight)/T)
        val lastK = (endMs - midnight) / T
        if (lastK < firstK) return emptyList()
        val out = ArrayList<Long>((lastK - firstK + 1).toInt().coerceAtMost(1_000_000))
        var k = firstK
        while (k <= lastK) {
            out.add(midnight + k * T)
            k++
        }
        return out
    }

    /**
     * グリッド吸着：各 g について [g-W, g+W] 内の最良 1 件（|Δ| 最小、同差は過去側）を選択。
     * records は createdAt 昇順想定。
     */
    private inline fun <T> snapToGrid(
        records: List<T>,
        grid: List<Long>,
        W: Long,
        crossinline ts: (T) -> Long
    ): List<PickupSlot> {
        val slots = ArrayList<PickupSlot>(grid.size)
        var p = 0 // レコード走査位置（単調増加）
        for (g in grid) {
            val left = g - W
            val right = g + W
            // 左端までポインタを進める
            while (p < records.size && ts(records[p]) < left) p++

            var bestIdx = -1
            var bestAbs = Long.MAX_VALUE
            var q = p
            // 右端まで探索
            while (q < records.size) {
                val t = ts(records[q])
                if (t > right) break
                val ad = abs(t - g)
                if (ad < bestAbs) {
                    bestAbs = ad
                    bestIdx = q
                } else if (ad == bestAbs) {
                    // 同差は「過去側」（小さい t）を優先 → 既に先頭から昇順なので先に見つかった方でOK
                    // ただし将来「未来優先」に変える場合はここを調整
                }
                q++
            }

            if (bestIdx >= 0) {
                val chosen = records[bestIdx]
                slots.add(PickupSlot(idealMs = g, sample = chosen as LocationSample, deltaMs = ts(chosen) - g))
                // p は戻さない（単調増加でOK）
            } else {
                slots.add(PickupSlot(idealMs = g, sample = null, deltaMs = null))
            }
        }
        return slots
    }

    // StateFlow 更新ヘルパ（Kotlin 1.9 互換）
    private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
        this.value = block(this.value)
    }
}
