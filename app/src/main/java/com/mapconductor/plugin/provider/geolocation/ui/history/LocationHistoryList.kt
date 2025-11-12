package com.mapconductor.plugin.provider.geolocation.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mapconductor.plugin.provider.geolocation.condition.SelectedSlot
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters.LoggingList
import com.mapconductor.plugin.provider.storageservice.room.LocationSample


@Composable
fun LocationHistoryList(
    records: List<LocationSample>,
    modifier: Modifier = Modifier
) {
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


    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(records, key = { it.id }) { item ->
            val slot = SelectedSlot(idealMs = 0L, sample = item, deltaMs = 0L)
            LoggingList(slot)
            HorizontalDivider()
        }
    }
}
