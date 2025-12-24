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

/**
 * Coarse classification of provider strings used across the UI.
 *
 * This is the shared entry point for deciding whether a sample is
 * treated as GPS, DeadReckoning, Network, or Other so map overlays,
 * history views, and debug panels stay consistent. The logic itself
 * lives in [providerKind].
 */
enum class ProviderKind {
    Gps,
    DeadReckoning,
    Network,
    Other
}

object Formatters {
    private val ICON_SIZE: Dp = 16.dp
    private val SPACER_SIZE: Dp = 4.dp
    private val zoneJst: ZoneId = ZoneId.of("Asia/Tokyo")
    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy/MM/dd(E) HH:mm:ss", Locale.JAPAN)

    fun timeJst(ms: Long?): String =
        ms?.let { timeFmt.format(Instant.ofEpochMilli(it).atZone(zoneJst)) } ?: "-"

    /**
     * Classify provider string into coarse categories.
     *
     * The logic behind this classifier is shared between the map screen,
     * history list, and any other UI that needs to distinguish GPS and
     * DeadReckoning providers.
     */
    fun providerKind(raw: String?): ProviderKind {
        val v = raw?.trim()?.lowercase(Locale.ROOT) ?: return ProviderKind.Other
        return when {
            v.contains("dead") || v == "dr" -> ProviderKind.DeadReckoning
            v == "gps" || v == "fused" || v.contains("gnss") || v.contains("satellite") ->
                ProviderKind.Gps
            v.contains("network") -> ProviderKind.Network
            else -> ProviderKind.Other
        }
    }

    /**
     * Provider string to friendly display text.
     * - Dead reckoning identifiers ("dead", "deadreckoning", "dr") -> "DeadReckoning"
     * - GPS, fused, and network values are normalized to "GPS" or "Network"
     * - Null or blank -> "-"
     * - Otherwise the raw string is returned.
     */
    fun providerText(raw: String?): String =
        when (providerKind(raw)) {
            ProviderKind.DeadReckoning -> "DeadReckoning"
            ProviderKind.Gps -> "GPS"
            ProviderKind.Network -> "Network"
            ProviderKind.Other -> raw?.takeIf { it.isNotBlank() } ?: "-"
        }

    fun batteryText(pct: Int?, charging: Boolean?): String =
        if (pct == null) {
            "-"
        } else {
            buildString {
                append("$pct%")
                if (charging == true) append(" (Charging)")
            }
        }

    fun headingText(deg: Float?): String =
        deg?.let { "${oneDecimal(it)} deg" } ?: "-"

    fun courseText(deg: Float?): String =
        deg?.let { "${oneDecimal(it)} deg" } ?: "-"

    /** m/s -> "0.0 Km/h (0.0 m/s)". Returns "-" when null. */
    fun speedText(mps: Float?): String =
        mps?.let { "${oneDecimal(it * 3.6f)} Km/h (${oneDecimal(it)} m/s)" } ?: "-"

    fun gnssUsedTotal(used: Int?, total: Int?): String =
        if (used == null || total == null) "-" else "$used/$total"

    fun cn0Text(cn0: Float?): String =
        cn0?.let { "${oneDecimal(it)} dB-Hz" } ?: "-"

    fun latLonAcc(lat: Double, lon: Double, acc: Float): String =
        "Lat=${"%.6f".format(lat)}, Lng=${"%.6f".format(lon)}, Acc=${"%.2f".format(acc)} m"

    private fun oneDecimal(x: Float): String =
        ((x * 10f).roundToInt() / 10f).toString()

    /**
     * Logging row used by history and Pickup screens.
     *
     * The layout is:
     * - Line 1: provider classification and raw provider name
     * - Line 2: GNSS used/total and mean C/N0
     * - Line 3: ideal timestamp and delta from the ideal grid
     * - Line 4: actual sample time and battery state
     * - Line 5: lat/lon/accuracy
     * - Line 6: heading, course, and speed
     */
    @Composable
    fun LoggingList(
        slot: SelectedSlot,
        modifier: Modifier = Modifier
    ) {
        val sample = slot.sample

        val provider = providerText(sample?.provider)
        val providerRaw = sample?.provider ?: "-"
        val gnss = gnssUsedTotal(sample?.gnssUsed, sample?.gnssTotal)
        val cn0 = cn0Text(sample?.cn0?.toFloat())
        val idealJst = timeJst(slot.idealMs)
        val delta = slot.deltaMs?.let { "${it} ms" }
        val time = timeJst(sample?.timeMillis)
        val battery = batteryText(sample?.batteryPercent, sample?.isCharging)
        val latlon = if (sample != null) {
            latLonAcc(sample.lat, sample.lon, sample.accuracy)
        } else {
            "-"
        }
        val head = headingText(sample?.headingDeg?.toFloat())
        val course = courseText(sample?.courseDeg?.toFloat())
        val speed = speedText(sample?.speedMps?.toFloat())

        Column(
            modifier = modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Line 1: provider classification and raw provider
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.GpsFixed,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Provider")
                Text(provider, style = MaterialTheme.typography.bodyMedium)

                if (providerRaw.isNotBlank() && providerRaw != provider) {
                    Text(" (", style = MaterialTheme.typography.bodyMedium)
                    Text(providerRaw, style = MaterialTheme.typography.bodyMedium)
                    Text(")", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Line 2: GNSS / C/N0
            if (sample != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.SignalCellularAlt,
                        contentDescription = null,
                        modifier = Modifier.size(ICON_SIZE)
                    )
                    Spacer(Modifier.width(SPACER_SIZE))
                    BoldLabel("GNSS")
                    Text(gnss, style = MaterialTheme.typography.bodyMedium)

                    Text(" / ", style = MaterialTheme.typography.bodyMedium)

                    Icon(
                        Icons.Outlined.SignalCellularAlt,
                        contentDescription = null,
                        modifier = Modifier.size(ICON_SIZE)
                    )
                    Spacer(Modifier.width(SPACER_SIZE))
                    BoldLabel("C/N0")
                    Text(cn0, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Line 3: Ideal / Delta t
            if (slot.idealMs != 0L || (slot.deltaMs ?: 0L) != 0L) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(ICON_SIZE)
                    )
                    Spacer(Modifier.width(SPACER_SIZE))
                    BoldLabel("Ideal")
                    Text(idealJst, style = MaterialTheme.typography.bodyMedium)
                    if (delta != null) {
                        Spacer(Modifier.width(SPACER_SIZE))
                        Icon(
                            Icons.Outlined.Timeline,
                            contentDescription = null,
                            modifier = Modifier.size(ICON_SIZE)
                        )
                        Spacer(Modifier.width(SPACER_SIZE))
                        BoldLabel("Delta t")
                        Text(delta, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Line 4: Time / Battery
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.AccessTime,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Time")
                Text(time, style = MaterialTheme.typography.bodyMedium)

                Text(" / ", style = MaterialTheme.typography.bodyMedium)

                Icon(
                    Icons.Outlined.BatteryFull,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Battery")
                Text(battery, style = MaterialTheme.typography.bodyMedium)
            }

            // Line 5: Lat/Lon/Acc
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Lat/Lon/Acc")
                Text(latlon, style = MaterialTheme.typography.bodyMedium)
            }

            // Line 6: Heading / Course / Speed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CompassCalibration,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Heading")
                Text(head, style = MaterialTheme.typography.bodyMedium)

                Text(" / ", style = MaterialTheme.typography.bodyMedium)

                Icon(
                    Icons.Outlined.Explore,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Course")
                Text(course, style = MaterialTheme.typography.bodyMedium)

                Text(" / ", style = MaterialTheme.typography.bodyMedium)

                Icon(
                    Icons.Outlined.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(SPACER_SIZE))
                BoldLabel("Speed")
                Text(speed, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    private fun BoldLabel(label: String) {
        Text(
            "$label : ",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

