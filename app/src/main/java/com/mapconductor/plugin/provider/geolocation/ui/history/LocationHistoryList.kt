package com.mapconductor.plugin.provider.geolocation.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CompassCalibration
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import com.mapconductor.plugin.provider.geolocation.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters

@Composable
fun LocationHistoryList(
    records: List<LocationSample>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(records, key = { it.id }) { item ->
            HistoryRow(item = item, modifier = Modifier.fillMaxWidth())
            Divider()
        }
    }
}

@Composable
fun HistoryRow(
    item: LocationSample,
    modifier: Modifier = Modifier
) {
    val time   = Formatters.timeJst(item.createdAt)
    val prov   = Formatters.providerText(item.provider)     // 例: "GPS" / "IMU(DeadReckoning)"
    val bat    = Formatters.batteryText(item.batteryPct, item.isCharging)
    val loc    = Formatters.latLonAcc(item.lat, item.lon, item.accuracy)
    val head   = Formatters.headingText(item.headingDeg)
    val course = Formatters.courseText(item.courseDeg)
    val speed  = Formatters.speedText(item.speedMps)
    val gnss   = Formatters.gnssUsedTotal(item.gnssUsed, item.gnssTotal)
    val cn0    = Formatters.cn0Text(item.gnssCn0Mean)

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1行目: [時計] Time
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccessTime, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("Time")
            Text(time, style = MaterialTheme.typography.bodyMedium)
        }

        // 2行目: 同一行に [アンテナ] Provider / [電池] Battery
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SettingsInputAntenna, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("Provider")
            Text(prov, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            Icon(Icons.Outlined.BatteryFull, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("Battery")
            Text(bat, style = MaterialTheme.typography.bodyMedium)
        }

        // 3行目: [地球] Location
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Public, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("Location")
            Text(loc, style = MaterialTheme.typography.bodyMedium)
        }

        // 4行目: [コンパス] Heading / [矢印] Course / [スピード] Speed
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CompassCalibration, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("Heading")
            Text(head, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            Icon(Icons.Outlined.Explore, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("Course")
            Text(course, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            Icon(Icons.Outlined.SatelliteAlt, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("Speed")
            Text(speed, style = MaterialTheme.typography.bodyMedium)
        }

        // 5行目: [衛星] GNSS / [電波強度] C/N0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SatelliteAlt, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("GNSS")
            Text(gnss, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            Icon(Icons.Outlined.SignalCellularAlt, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            BoldLabel("C/N0")
            Text(cn0, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BoldLabel(label: String) {
    Text("$label : ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
}
