package com.mapconductor.plugin.provider.geolocation.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CompassCalibration
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters
import com.mapconductor.plugin.provider.storageservice.room.LocationSample

private val ICON_SIZE: Dp = 18.dp

@Composable
fun LocationHistoryList(
    records: List<LocationSample>,
    modifier: Modifier = Modifier
) {
    // 空プレースホルダ
    if (records.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "まだ記録がありません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(records) { item ->
            HistoryRow(item = item, modifier = Modifier.fillMaxWidth())
            Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}

/** 1レコード行 */
@Composable
fun HistoryRow(
    item: LocationSample,
    modifier: Modifier = Modifier
) {
    val time    = Formatters.timeJst(item.timeMillis)
    val prov    = Formatters.providerText(item.provider)
    val latlon  = Formatters.latLonAcc(item.lat, item.lon, item.accuracy)
    val head    = Formatters.headingText(item.headingDeg.toFloat())
    val course  = Formatters.courseText(item.courseDeg.toFloat())
    val speed   = Formatters.speedText(item.speedMps.toFloat())
    val gnss    = Formatters.gnssUsedTotal(item.gnssUsed, item.gnssTotal)
    val cn0     = Formatters.cn0Text(item.cn0.toFloat())
    val battery = Formatters.batteryText(item.batteryPercent, item.isCharging)

    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 1行目: Provider / [GNSS] Used/Total / [搬送波対雑音比] C/N0
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Outlined.GpsFixed, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Provider")
            Text(prov, style = MaterialTheme.typography.bodyMedium)

            if (prov == "GPS") {
                Text(" / ", style = MaterialTheme.typography.bodyMedium)

                androidx.compose.material3.Icon(
                    Icons.Outlined.SignalCellularAlt,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(6.dp))
                BoldLabel("GNSS")
                Text(gnss, style = MaterialTheme.typography.bodyMedium)

                Text(" / ", style = MaterialTheme.typography.bodyMedium)

                androidx.compose.material3.Icon(
                    Icons.Outlined.SignalCellularAlt,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE)
                )
                Spacer(Modifier.width(6.dp))
                BoldLabel("C/N0")
                Text(cn0, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // 2行目: [時計] 時刻 / [電池] Battery
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Time")
            Text(time, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            androidx.compose.material3.Icon(Icons.Outlined.BatteryFull, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Battery")
            Text(battery, style = MaterialTheme.typography.bodyMedium)
        }

        // 3行目: [位置] Lat/Lon/Acc
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Lat/Lon/Acc")
            Text(latlon, style = MaterialTheme.typography.bodyMedium)
        }

        // 4行目: [方位] Heading / Course / [速度] Speed
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Outlined.CompassCalibration, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Heading")
            Text(head, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            androidx.compose.material3.Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Course")
            Text(course, style = MaterialTheme.typography.bodyMedium)

            Text(" / ", style = MaterialTheme.typography.bodyMedium)

            androidx.compose.material3.Icon(Icons.Outlined.Speed, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(6.dp))
            BoldLabel("Speed")
            Text(speed, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BoldLabel(label: String) {
    Text("$label : ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
}
