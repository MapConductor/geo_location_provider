package com.mapconductor.plugin.provider.geolocation.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.config.UploadOutputFormat
import com.mapconductor.plugin.provider.geolocation.config.UploadSchedule
import com.mapconductor.plugin.provider.geolocation.fusion.ImsUseCase

private data class TimezoneOption(
    val zoneId: String,
    val label: String
)

private data class UseCaseOption(
    val value: ImsUseCase,
    val label: String
)

private val TIMEZONE_OPTIONS: List<TimezoneOption> =
    listOf(
        TimezoneOption("Pacific/Auckland", "+12H (Wellington, Auckland etc...)"),
        TimezoneOption("Pacific/Noumea", "+11H (Noumea etc...)"),
        TimezoneOption("Australia/Sydney", "+10H (Sydney, Guam etc...)"),
        TimezoneOption("Asia/Tokyo", "+9H (Tokyo, Seoul etc...)"),
        TimezoneOption("Asia/Singapore", "+8H (Beijing, Hong Kong, Singapore etc...)"),
        TimezoneOption("Asia/Bangkok", "+7H (Bangkok, Jakarta etc...)"),
        TimezoneOption("Asia/Dhaka", "+6H (Dhaka etc...)"),
        TimezoneOption("Asia/Karachi", "+5H (Karachi etc...)"),
        TimezoneOption("Asia/Dubai", "+4H (Dubai etc...)"),
        TimezoneOption("Asia/Riyadh", "+3H (Jeddah, Baghdad etc...)"),
        TimezoneOption("Africa/Cairo", "+2H (Cairo, Athens etc...)"),
        TimezoneOption("Europe/Paris", "+1H (Paris, Rome, Berlin etc...)"),
        TimezoneOption("Europe/London", "+0H (London etc...)"),
        TimezoneOption("Atlantic/Azores", "-1H (Azores etc...)"),
        TimezoneOption("America/Noronha", "-2H (etc...)"),
        TimezoneOption("America/Sao_Paulo", "-3H (Rio de Janeiro etc...)"),
        TimezoneOption("America/Santo_Domingo", "-4H (Santo Domingo etc...)"),
        TimezoneOption("America/New_York", "-5H (New York, Montreal etc...)"),
        TimezoneOption("America/Chicago", "-6H (Chicago, Mexico City etc...)"),
        TimezoneOption("America/Denver", "-7H (Denver etc...)"),
        TimezoneOption("America/Los_Angeles", "-8H (Los Angeles, Vancouver etc...)"),
        TimezoneOption("America/Anchorage", "-9H (Anchorage etc...)"),
        TimezoneOption("Pacific/Honolulu", "-10H (Honolulu etc...)"),
        TimezoneOption("Pacific/Midway", "-11H (Midway Islands etc...)")
    )

private val USE_CASE_OPTIONS: List<UseCaseOption> =
    listOf(
        UseCaseOption(ImsUseCase.WALK, "Walk"),
        UseCaseOption(ImsUseCase.BIKE, "Bike"),
        UseCaseOption(ImsUseCase.CAR, "Car"),
        UseCaseOption(ImsUseCase.TRAIN, "Train")
    )

@Composable
fun UploadSettingsScreen(
    vm: UploadSettingsViewModel = viewModel(),
    imsVm: ImsEkfSettingsViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val uploadEnabled by vm.uploadEnabled.collectAsState()
    val schedule by vm.schedule.collectAsState()
    val intervalSec by vm.intervalSec.collectAsState()
    val zoneId by vm.zoneId.collectAsState()
    val outputFormat by vm.outputFormat.collectAsState()
    val status by vm.status.collectAsState()

    val imsConfig by imsVm.config.collectAsState()

    val intervalText = remember(intervalSec) { mutableStateOf(intervalSec.toString()) }
    val formatState = remember(outputFormat) { mutableStateOf(outputFormat) }
    val allowedLatencyMsText =
        remember(imsConfig.allowedLatencyMs) { mutableStateOf(imsConfig.allowedLatencyMs.toString()) }
    val gpsIntervalOverrideMsText =
        remember(imsConfig.gpsIntervalMsOverride) {
            mutableStateOf(imsConfig.gpsIntervalMsOverride?.toString().orEmpty())
        }

    val timezoneExpanded = remember { mutableStateOf(false) }
    val selectedTimezone: TimezoneOption? =
        TIMEZONE_OPTIONS.firstOrNull { it.zoneId == zoneId }
    val timezoneLabel =
        selectedTimezone?.label ?: zoneId

    val useCaseExpanded = remember { mutableStateOf(false) }
    val useCaseLabel =
        USE_CASE_OPTIONS.firstOrNull { it.value == imsConfig.useCase }?.label ?: imsConfig.useCase.name

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                val enabled = uploadEnabled
                val alpha = if (enabled) 1f else 0.5f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(alpha)
                        .clickable(enabled = enabled) {
                            if (enabled) {
                                timezoneExpanded.value = true
                            }
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = timezoneLabel)
                    Text(text = "v")
                }

                DropdownMenu(
                    expanded = timezoneExpanded.value,
                    onDismissRequest = { timezoneExpanded.value = false }
                ) {
                    TIMEZONE_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                timezoneExpanded.value = false
                                vm.setZoneId(option.zoneId)
                            }
                        )
                    }
                }
            }

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

            Text("GPS correction (EKF)", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enabled")
                Switch(
                    checked = imsConfig.enabled,
                    onCheckedChange = { imsVm.setEnabled(it) }
                )
            }

            Text("Use case", style = MaterialTheme.typography.titleMedium)
            Box(modifier = Modifier.fillMaxWidth()) {
                val enabled = imsConfig.enabled
                val alpha = if (enabled) 1f else 0.5f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(alpha)
                        .clickable(enabled = enabled) {
                            if (enabled) {
                                useCaseExpanded.value = true
                            }
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = useCaseLabel)
                    Text(text = "v")
                }

                DropdownMenu(
                    expanded = useCaseExpanded.value,
                    onDismissRequest = { useCaseExpanded.value = false }
                ) {
                    USE_CASE_OPTIONS.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt.label) },
                            onClick = {
                                useCaseExpanded.value = false
                                imsVm.setUseCase(opt.value)
                            }
                        )
                    }
                }
            }

            Text("Allowed latency (ms)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                enabled = imsConfig.enabled,
                value = allowedLatencyMsText.value,
                onValueChange = { raw ->
                    allowedLatencyMsText.value = raw.filter { it.isDigit() }.take(8)
                    val ms = allowedLatencyMsText.value.toLongOrNull() ?: 0L
                    imsVm.setAllowedLatencyMs(ms)
                },
                label = { Text("0 = no extra smoothing") }
            )

            Text("GPS interval override (ms)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                enabled = imsConfig.enabled,
                value = gpsIntervalOverrideMsText.value,
                onValueChange = { raw ->
                    gpsIntervalOverrideMsText.value = raw.filter { it.isDigit() }.take(8)
                    val overrideMs =
                        gpsIntervalOverrideMsText.value.toLongOrNull()?.takeIf { it > 0L }
                    imsVm.setGpsIntervalOverrideMs(overrideMs)
                },
                label = { Text("Blank = use sampling interval") }
            )

            Text(
                text = "When enabled, corrected samples are saved as provider=gps_corrected and can be shown on the Map screen.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
