package com.mapconductor.plugin.provider.geolocation.ui.pickup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.storageservice.room.LocationSampleDao
import com.mapconductor.plugin.provider.geolocation.condition.SelectorCondition
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation.prefs.SelectorPrefs
import com.mapconductor.plugin.provider.geolocation.repository.SelectorRepository
import com.mapconductor.plugin.provider.geolocation.usecase.BuildSelectedSlots
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.floor
/**
 * ViewModel は app 側のみ。
 * dataselector は Prefs / Repository / UseCase(BuildSelectedSlots) を提供。
 */
class SelectorViewModel(
    app: Application,
    dao: LocationSampleDao,               // ★ 必要なのは DAO だけ
) : AndroidViewModel(app) {

    private val prefs = SelectorPrefs(app.applicationContext)
    private val repo = SelectorRepository(dao)
    private val buildSelectedSlots = BuildSelectedSlots(repo)

    /** 現在の条件（UIから参照/編集） */
    val condition: StateFlow<SelectorCondition> =
        prefs.conditionFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SelectorCondition()
        )

    /** 条件に応じた抽出済みスロット（欠測補完を含む） */
    val slots: StateFlow<List<SelectedSlot>> =
        condition
            .mapLatest { cond -> buildSelectedSlots(cond) } // suspend -> List を Flow 化
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    /** 条件更新（期間/件数/精度/間隔/並び順など） */
    suspend fun update(block: (SelectorCondition) -> SelectorCondition) {
        prefs.update(block)
    }

    // ------- Factory -------
    class Factory(
        private val app: Application,
        private val dao: LocationSampleDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SelectorViewModel(app, dao) as T
        }
    }
}

/**
 * 期間 [fromMillis, toMillis) を intervalMinutes 分で区切る。
 * newerFirst=true のとき To(期間終了)をアンカー、false のとき From(期間開始)をアンカーにする。
 * 返り値は [startMillis, endMillis) の半開区間(LongRange)のリスト。
 *
 * 既存UIはそのまま、グルーピング境界と順序のみを切り替える目的で使用。
 */
fun buildAnchoredBuckets(
    fromMillis: Long,
    toMillis: Long,
    intervalMinutes: Long,
    newerFirst: Boolean
): List<LongRange> {
    require(intervalMinutes > 0) { "intervalMinutes must be > 0" }
    require(toMillis >= fromMillis) { "toMillis must be >= fromMillis" }

    val step = intervalMinutes * 60_000L
    val out = ArrayList<LongRange>(128)

    if (!newerFirst) {
        // 古い方から（From アンカー）
        var start = fromMillis
        // start を step 境界に合わせる（from が境界上でない場合も安全）
        val misalign = (start - fromMillis) % step
        if (misalign != 0L) start -= misalign
        while (start < toMillis) {
            val end = (start + step).coerceAtMost(toMillis)
            if (start < end) out += LongRange(start, end)
            start += step
        }
    } else {
        // 新しい方から（To アンカー）
        var end = toMillis
        // end を step 境界に合わせる（to が境界上でない場合も安全）
        val misalign = (toMillis - end) % step
        if (misalign != 0L) end -= misalign
        while (end > fromMillis) {
            val start = (end - step).coerceAtLeast(fromMillis)
            if (start < end) out += LongRange(start, end)
            end -= step
        }
    }
    return out
}

/**
 * （必要なら）LongRange→ZonedDateTime 整形用の補助。
 * 既存のFormatterを使う場合は無理に使わなくてOK。
 */
fun LongRange.asZoned(zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime> {
    val s = ZonedDateTime.ofInstant(Instant.ofEpochMilli(this.first), zone)
    val e = ZonedDateTime.ofInstant(Instant.ofEpochMilli(this.last), zone)
    return s to e
}
