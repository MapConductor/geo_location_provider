package com.mapconductor.plugin.provider.geolocation.ui.map

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit

internal object MapRangeSpecParser {

    sealed class Result {
        data class Ok(val spec: Spec) : Result()
        data class Error(val message: String) : Result()
    }

    sealed class Spec {
        data object None : Spec()

        /**
         * Half-open time window [fromMillisInclusive, toMillisExclusive).
         */
        data class TimeWindow(
            val fromMillisInclusive: Long,
            val toMillisExclusive: Long
        ) : Spec()

        /**
         * Offset window, indexed from newest (0 = latest).
         *
         * - startOffset <= endOffsetInclusive.
         * - Both are clamped by caller to available sample size.
         */
        data class OffsetWindow(
            val startOffset: Int,
            val endOffsetInclusive: Int
        ) : Spec()
    }

    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu/MM/dd")
            .withResolverStyle(ResolverStyle.STRICT)

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT)

    fun parse(
        raw: String,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Ok(Spec.None)

        // Offset range: n-m (digits only).
        run {
            val m = OFFSET_RANGE.matchEntire(text)
            if (m != null) {
                val a = m.groupValues[1].toIntOrNull()
                val b = m.groupValues[2].toIntOrNull()
                if (a == null || b == null) return Result.Error("Invalid offset range.")
                val start = minOf(a, b)
                val end = maxOf(a, b)
                return Result.Ok(Spec.OffsetWindow(startOffset = start, endOffsetInclusive = end))
            }
        }

        // Date range: yyyy/MM/dd - yyyy/MM/dd
        run {
            val m = DATE_RANGE.matchEntire(text)
            if (m != null) {
                val a = parseDate(m.groupValues[1]) ?: return Result.Error("Invalid date.")
                val b = parseDate(m.groupValues[2]) ?: return Result.Error("Invalid date.")
                val (startDate, endDate) = if (a <= b) a to b else b to a
                val from = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
                val toExclusive = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                return Result.Ok(Spec.TimeWindow(fromMillisInclusive = from, toMillisExclusive = toExclusive))
            }
        }

        // Single date: yyyy/MM/dd
        run {
            val d = parseDate(text)
            if (d != null) {
                val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
                val toExclusive = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                return Result.Ok(Spec.TimeWindow(fromMillisInclusive = from, toMillisExclusive = toExclusive))
            }
        }

        // Datetime range: yyyy/MM/dd[_ ]HH:mm:ss - yyyy/MM/dd[_ ]HH:mm:ss
        run {
            val m = DATETIME_RANGE.matchEntire(text)
            if (m != null) {
                val a = parseDateTime(m.groupValues[1], zone) ?: return Result.Error("Invalid date/time.")
                val b = parseDateTime(m.groupValues[2], zone) ?: return Result.Error("Invalid date/time.")
                val (start, end) = if (a <= b) a to b else b to a
                val from = start.toInstant().toEpochMilli()
                val toExclusive = end.plusSeconds(1).toInstant().toEpochMilli()
                return Result.Ok(Spec.TimeWindow(fromMillisInclusive = from, toMillisExclusive = toExclusive))
            }
        }

        // Single datetime: yyyy/MM/dd[_ ]HH:mm:ss (from that time to now).
        run {
            val dt = parseDateTime(text, zone)
            if (dt != null) {
                val from = dt.toInstant().toEpochMilli()
                val toExclusive = safePlusMillis(nowMillis, 1L)
                return Result.Ok(Spec.TimeWindow(fromMillisInclusive = from, toMillisExclusive = toExclusive))
            }
        }

        return Result.Error(
            "Unsupported range format. Use yyyy/MM/dd, yyyy/MM/dd-yyyy/MM/dd, " +
                "yyyy/MM/dd_HH:mm:ss, yyyy/MM/dd_HH:mm:ss-yyyy/MM/dd_HH:mm:ss, or n-m."
        )
    }

    private fun parseDate(text: String): LocalDate? =
        runCatching { LocalDate.parse(text.trim(), dateFmt) }.getOrNull()

    private fun parseDateTime(text: String, zone: ZoneId): java.time.ZonedDateTime? {
        val t = text.trim().replace(' ', '_')
        val m = DATETIME_SINGLE.matchEntire(t) ?: return null
        val d = parseDate(m.groupValues[1]) ?: return null
        val lt = runCatching { LocalTime.parse(m.groupValues[2], timeFmt) }.getOrNull() ?: return null
        val ldt = LocalDateTime.of(d, lt)
        return ldt.atZone(zone).truncatedTo(ChronoUnit.SECONDS)
    }

    private fun safePlusMillis(v: Long, delta: Long): Long {
        val out = v + delta
        return if (delta > 0 && out < v) Long.MAX_VALUE else out
    }

    private val OFFSET_RANGE = Regex("""^\s*(\d+)\s*-\s*(\d+)\s*$""")
    private val DATE_RANGE = Regex("""^\s*(\d{4}/\d{2}/\d{2})\s*-\s*(\d{4}/\d{2}/\d{2})\s*$""")
    private val DATETIME_SINGLE = Regex("""^\s*(\d{4}/\d{2}/\d{2})_(\d{2}:\d{2}:\d{2})\s*$""")
    private val DATETIME_RANGE =
        Regex(
            """^\s*(\d{4}/\d{2}/\d{2}[_ ]\d{2}:\d{2}:\d{2})\s*-\s*(\d{4}/\d{2}/\d{2}[_ ]\d{2}:\d{2}:\d{2})\s*$"""
        )
}

