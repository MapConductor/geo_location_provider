package com.mapconductor.plugin.provider.geolocation.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CompassCalibration
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Formatters {
    private val ICON_SIZE: Dp = 16.dp
    private val SPACER_SIZE: Dp = 4.dp
    private val zoneJst = ZoneId.of("Asia/Tokyo")
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) HH:mm:ss", Locale.JAPAN)

    fun timeJst(ms: Long?): String =
        ms?.let { timeFmt.format(Instant.ofEpochMilli(it).atZone(zoneJst)) } ?: "-"

    /**
     * Provider string -> friendly text.
     * - Dead reckoning identifiers ("dead", "deadreckoning", "dr") -> "DeadReckoning"
     * - GPS/fused/network values are normalized to "GPS" / "Network"
     * - Null/blank -> "-"
     * - Otherwise returns the raw string.
     */
    fun providerText(raw: String?): String {
        val v = raw?.trim()?.lowercase(Locale.ROOT) ?: return "-"
        return when {
            v.contains("dead") || v == "dr" -> "DeadReckoning"
            v == "gps" || v == "fused" || v.contains("gnss") || v.contains("satellite") -> "GPS"
            v.contains("network") -> "Network"
            else -> raw
        }
    }

    fun batteryText(pct: Int?, charging: Boolean?): String =
        if (pct == null) "-" else buildString {
            append("$pct%")
            if (charging == true) append(" (Charging)")
        }

    fun headingText(deg: Float?): String =
        deg?.let { "${oneDecimal(it)}°" } ?: "-"

    fun courseText(deg: Float?): String =
        deg?.let { "${oneDecimal(it)}°" } ?: "-"

    /** m/s -> "0.0Km/h(0.0m/s)"（無いとき "-"） */
    fun speedText(mps: Float?): String =
        mps?.let { "${oneDecimal(it * 3.6f)} Km/h (${oneDecimal(it)} m/s)" } ?: "-"

    fun gnssUsedTotal(used: Int?, total: Int?): String =
        if (used == null || total == null) "-" else "$used/$total"

    fun cn0Text(cn0: Float?): String = cn0?.let { "${oneDecimal(it)} dB-Hz" } ?: "-"

    fun latLonAcc(lat: Double, lon: Double, acc: Float): String =
        "Lat=${"%.6f".format(lat)}, Lng=${"%.6f".format(lon)}, Acc=${"%.2f".format(acc)} m"

    private fun oneDecimal(x: Float): String = ((x * 10f).roundToInt() / 10f).toString()

    @Composable
    fun LoggingList(
        slot: SelectedSlot,
        modifier: Modifier = Modifier
    ) {
        val item = slot.sample?: return

        val time     = timeJst(item.timeMillis)
        val prov     = providerText(item.provider)
        val latlon   = latLonAcc(item.lat, item.lon, item.accuracy)
        val head     = headingText(item.headingDeg.toFloat())
        val course   = courseText(item.courseDeg.toFloat())
        val speed    = speedText(item.speedMps.toFloat())
        val gnss     = gnssUsedTotal(item.gnssUsed, item.gnssTotal)
        val cn0      = cn0Text(item.cn0.toFloat())
        val battery  = batteryText(item.batteryPercent, item.isCharging)
        val idealJst = timeJst(slot.idealMs)
        val delta    = slot.deltaMs?.let { d ->
            val sign = if (d >= 0) "+" else "-"
            val ad = kotlin.math.abs(d)
            val sec = ad / 1000
            "$sign${sec}s"
        }

        Column(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 1行目: Provider / [GNSS] Used/Total / [搬送波対雑音比] C/N0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GpsFixed, contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Provider")
                Text(prov, style = MaterialTheme.typography.bodyMedium)

                if (prov == "GPS") {
                    Text(" / ", style = MaterialTheme.typography.bodyMedium)

                    Icon(Icons.Outlined.SignalCellularAlt, contentDescription = null,
                        modifier = Modifier.size(ICON_SIZE))
                    Spacer(Modifier.width(SPACER_SIZE))
                    BoldLabel("GNSS")
                    Text(gnss, style = MaterialTheme.typography.bodyMedium)

                    Text(" / ", style = MaterialTheme.typography.bodyMedium)

                    Icon(Icons.Outlined.SignalCellularAlt, contentDescription = null,
                        modifier = Modifier.size(ICON_SIZE))
                    Spacer(Modifier.width(SPACER_SIZE))
                    BoldLabel("C/N0")
                    Text(cn0, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // 2行目: Ideal / Δ
            if ((slot.idealMs != 0L) || (slot.deltaMs != 0L)) {
                // Ideal=0,Delta=0なら主画面なのでこの行は非表示
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null,
                        modifier = Modifier.size(ICON_SIZE))
                    Spacer(Modifier.width(SPACER_SIZE))
                    BoldLabel("Ideal")
                    Text(idealJst, style = MaterialTheme.typography.bodyMedium)
                    if (delta != null) {
                        Spacer(Modifier.width(SPACER_SIZE))
                        Icon(Icons.Outlined.Timeline, contentDescription = null,
                            modifier = Modifier.size(ICON_SIZE))
                        Spacer(Modifier.width(SPACER_SIZE))
                        BoldLabel("Δ")
                        Text(delta, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // 3行目: [時計] 時刻 / [電池] Battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccessTime, contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Time")
                Text(time, style = MaterialTheme.typography.bodyMedium)

                Text(" / ", style = MaterialTheme.typography.bodyMedium)

                Icon(Icons.Outlined.BatteryFull, contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Battery")
                Text(battery, style = MaterialTheme.typography.bodyMedium)
            }

            // 4行目: [位置] Lat/Lon/Acc
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Lat/Lon/Acc")
                Text(latlon, style = MaterialTheme.typography.bodyMedium)
            }

            // 5行目: [方位] Heading / Course / [速度] Speed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CompassCalibration, contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Heading")
                Text(head, style = MaterialTheme.typography.bodyMedium)

                Text(" / ", style = MaterialTheme.typography.bodyMedium)

                Icon(Icons.Outlined.Explore, contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Course")
                Text(course, style = MaterialTheme.typography.bodyMedium)

                Text(" / ", style = MaterialTheme.typography.bodyMedium)

                Icon(Icons.Outlined.Speed, contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE))
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Speed")
                Text(speed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun BoldLabel(label: String) {
        Text("$label : ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}
