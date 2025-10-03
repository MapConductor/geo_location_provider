package com.mapconductor.plugin.provider.geolocation.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Formatters {
    private val zoneJst = ZoneId.of("Asia/Tokyo")
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) HH:mm:ss'.00'", Locale.JAPAN)

    fun timeJst(ms: Long?): String =
        ms?.let { timeFmt.format(Instant.ofEpochMilli(it).atZone(zoneJst)) } ?: "-"

    fun providerText(raw: String?): String =
        when (raw?.lowercase(Locale.ROOT)) {
            null      -> "-"
            "fused"   -> "Fused"
            "gps"     -> "GPS"
            "network" -> "Network"
            else      -> raw
        }

    fun batteryText(percent: Int?, charging: Boolean?): String =
        if (percent == null || charging == null) "-" else "${percent}%(${if (charging) "充電中" else "未充電"})"

    fun headingText(deg: Float?): String = deg?.let { "${oneDecimal(it)}°" } ?: "-"
    fun courseText(deg: Float?): String  = deg?.let { "${oneDecimal(it)}°" } ?: "-"

    /** m/s -> "0.0Km/h(0.0m/s)"（無いとき "-"） */
    fun speedText(mps: Float?): String =
        mps?.let { "${oneDecimal(it * 3.6f)}Km/h(${oneDecimal(it)}m/s)" } ?: "-"

    fun gnssUsedTotal(used: Int?, total: Int?): String =
        if (used == null || total == null) "-" else "$used/$total"

    fun cn0Text(cn0: Float?): String = cn0?.let { "${oneDecimal(it)}dB-Hz" } ?: "-"

    fun latLonAcc(lat: Double, lon: Double, acc: Float): String =
        "[${"%.6f".format(lon)}], [${"%.6f".format(lat)}], [${"%.2f".format(acc)}m]"

    private fun oneDecimal(x: Float): String = ((x * 10f).roundToInt() / 10f).toString()
}
