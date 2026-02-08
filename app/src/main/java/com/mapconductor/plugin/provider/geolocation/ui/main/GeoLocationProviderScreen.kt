package com.mapconductor.plugin.provider.geolocation.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.ui.history.HistoryViewModel
import com.mapconductor.plugin.provider.geolocation.ui.history.LocationHistoryList
import com.mapconductor.plugin.provider.storageservice.prefs.DrMode

/**
 * Entry point for the main screen.
 *
 * - Shows Interval / DR interval [Save&Apply] controls and the history list.
 * - The history list is driven by the ViewModel; UI does not touch Room directly.
 */
@Composable
fun GeoLocationProviderScreen(
    state: UiState,
    onButtonClick: () -> Unit
) {
    val ctx = LocalContext.current
    val intervalVm: IntervalSettingsViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return IntervalSettingsViewModel(ctx) as T
            }
        }
    )
    val historyVm: HistoryViewModel = viewModel()
    val records by historyVm.items.collectAsState(initial = emptyList())

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                IntervalAndDrArea(intervalVm)

                HorizontalDivider(
                    thickness = DividerDefaults.Thickness,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // History area; UI does not touch Room directly.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val configuration = LocalConfiguration.current
                    val isCompact = configuration.screenWidthDp < 360
                    val density = LocalDensity.current
                    val firstRowHeightPx = remember { mutableStateOf(0) }

                    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp) {
                        firstRowHeightPx.value = 0
                    }

                    val availableHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(0f)
                    val fallbackRowHeightPx =
                        with(density) { (if (isCompact) 240.dp else 160.dp).toPx() }
                            .coerceAtLeast(1f)
                    val rowHeightPx =
                        firstRowHeightPx.value.toFloat().takeIf { it > 0f } ?: fallbackRowHeightPx

                    val computed =
                        (availableHeightPx / rowHeightPx).toInt()
                            .coerceAtLeast(3)
                            .coerceAtMost(50)
                    LaunchedEffect(computed) {
                        historyVm.setBufferLimit(computed)
                    }
                    LocationHistoryList(
                        records = records,
                        onFirstRowHeightPx = { px -> firstRowHeightPx.value = px },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun IntervalAndDrArea(
    vm: IntervalSettingsViewModel
) {
    val sec by vm.secondsText.collectAsState()
    val dr by vm.drIntervalText.collectAsState()
    val drGps by vm.drGpsIntervalText.collectAsState()
    val mode by vm.drMode.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = sec,
            onValueChange = {
                vm.onSecondsChanged(it.filter { c -> c.isDigit() }.take(3))
            },
            label = { Text("GPS interval (sec)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = dr,
            onValueChange = {
                vm.onDrIntervalChanged(it.filter { c -> c.isDigit() }.take(3))
            },
            label = { Text("DR interval (sec)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(onClick = { vm.saveAndApply() }) {
            Text("Save & Apply")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = drGps,
            onValueChange = {
                vm.onDrGpsIntervalChanged(it.filter { c -> c.isDigit() }.take(3))
            },
            label = { Text("DR GPS interval (sec)") },
            enabled = mode == DrMode.Prediction,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Keep layout consistent with the top row button.
        Spacer(modifier = Modifier.width(104.dp))
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "DR mode",
            modifier = Modifier.width(96.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = mode == DrMode.Prediction,
                onClick = { vm.onDrModeChanged(DrMode.Prediction) }
            )
            Text("Prediction")

            Spacer(modifier = Modifier.width(8.dp))

            RadioButton(
                selected = mode == DrMode.Completion,
                onClick = { vm.onDrModeChanged(DrMode.Completion) }
            )
            Text("Completion")
        }
    }
}
