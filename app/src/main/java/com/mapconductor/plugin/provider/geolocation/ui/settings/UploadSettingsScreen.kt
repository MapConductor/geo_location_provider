package com.mapconductor.plugin.provider.geolocation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.config.UploadOutputFormat
import com.mapconductor.plugin.provider.geolocation.config.UploadSchedule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadSettingsScreen(
    vm: UploadSettingsViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val uploadEnabled by vm.uploadEnabled.collectAsState()
    val schedule by vm.schedule.collectAsState()
    val intervalSec by vm.intervalSec.collectAsState()
    val zoneId by vm.zoneId.collectAsState()
    val outputFormat by vm.outputFormat.collectAsState()
    val status by vm.status.collectAsState()

    val intervalText = remember(intervalSec) { mutableStateOf(intervalSec.toString()) }
    val zoneText = remember(zoneId) { mutableStateOf(zoneId) }
    val formatState = remember(outputFormat) { mutableStateOf(outputFormat) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Upload on/off
            Text("Upload", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row {
                    RadioButton(
                        selected = !uploadEnabled,
                        onClick = { vm.setUploadEnabled(false) }
                    )
                    Text("Do not upload")
                }
                Row {
                    RadioButton(
                        selected = uploadEnabled,
                        onClick = { vm.setUploadEnabled(true) }
                    )
                    Text("Upload")
                }
            }

            // Upload schedule
            Text("Upload schedule", style = MaterialTheme.typography.titleMedium)
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    RadioButton(
                        enabled = uploadEnabled,
                        selected = uploadEnabled && schedule == UploadSchedule.NIGHTLY,
                        onClick = { if (uploadEnabled) vm.setSchedule(UploadSchedule.NIGHTLY) }
                    )
                    Text("Nightly backup")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    RadioButton(
                        enabled = uploadEnabled,
                        selected = uploadEnabled && schedule == UploadSchedule.REALTIME,
                        onClick = { if (uploadEnabled) vm.setSchedule(UploadSchedule.REALTIME) }
                    )
                    Text("Realtime upload")
                }
            }

            // Interval
            Text("Interval (seconds)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                enabled = uploadEnabled && schedule == UploadSchedule.REALTIME,
                value = intervalText.value,
                onValueChange = { raw ->
                    val cleaned = raw.filter { it.isDigit() }
                    intervalText.value = cleaned
                    val sec = cleaned.toIntOrNull() ?: 0
                    vm.setIntervalSec(sec)
                },
                label = { Text("0 = every sample, 1-86400") }
            )

            // Timezone
            Text("Timezone", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                enabled = uploadEnabled,
                value = zoneText.value,
                onValueChange = { v ->
                    zoneText.value = v
                    vm.setZoneId(v)
                },
                label = { Text("IANA ID (e.g. Asia/Tokyo)") }
            )

            // Output format
            Text("Output format", style = MaterialTheme.typography.titleMedium)
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    RadioButton(
                        enabled = uploadEnabled,
                        selected = formatState.value == UploadOutputFormat.GEOJSON,
                        onClick = {
                            if (uploadEnabled) {
                                formatState.value = UploadOutputFormat.GEOJSON
                                vm.setOutputFormat(UploadOutputFormat.GEOJSON)
                            }
                        }
                    )
                    Text("GeoJSON")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    RadioButton(
                        enabled = uploadEnabled,
                        selected = formatState.value == UploadOutputFormat.GPX,
                        onClick = {
                            if (uploadEnabled) {
                                formatState.value = UploadOutputFormat.GPX
                                vm.setOutputFormat(UploadOutputFormat.GPX)
                            }
                        }
                    )
                    Text("GPX")
                }
            }

            if (status.isNotBlank()) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text(status)
            }

            // Quick hint for behavior
            Text(
                text = "When interval is 0 or equals the sampling interval, uploads are triggered on every new sample.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
