package com.mapconductor.plugin.provider.geolocation.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.mapconductor.core.features.GeoPointImpl
import com.mapconductor.core.map.MapCameraPositionImpl
import com.mapconductor.core.marker.DefaultIcon
import com.mapconductor.core.marker.Marker
import com.mapconductor.googlemaps.GoogleMapsView
import com.mapconductor.googlemaps.rememberGoogleMapViewState

/**
 * Map screen that shows markers for LocationSample rows.
 *
 * - Checkboxes: GPS and DeadReckoning filter providers.
 * - Display count: limits the number of markers; newest samples are kept.
 * - Marker appearance is delegated to the map library; this Composable only
 *   selects label based on provider.
 */
@Composable
fun MapScreen() {
    val vm: MapViewModel = viewModel()
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is MapViewModel.Event.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val centerPoint = state.latest?.let { sample ->
        GeoPointImpl.fromLatLong(sample.lat, sample.lon)
    } ?: GeoPointImpl.fromLatLong(35.6812, 139.7671)

    val camera = MapCameraPositionImpl(
        position = centerPoint,
        zoom = 14.0
    )

    val mapViewState = rememberGoogleMapViewState(
        cameraPosition = camera
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = state.gpsChecked,
                onCheckedChange = { vm.onGpsCheckedChange(it) },
                enabled = !state.filterApplied
            )
            Text(text = "GPS")

            Checkbox(
                checked = state.drChecked,
                onCheckedChange = { vm.onDrCheckedChange(it) },
                modifier = Modifier.padding(start = 16.dp),
                enabled = !state.filterApplied
            )
            Text(text = "DeadReckoning")

            OutlinedTextField(
                value = state.limitText,
                onValueChange = { vm.onLimitChanged(it) },
                label = { Text("Count (1-1000)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                 enabled = !state.filterApplied,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f)
            )

            Button(
                onClick = { vm.onApplyClicked() },
                modifier = Modifier.padding(start = 16.dp)
            ) {
                val label = if (state.filterApplied) "Cancel" else "Apply"
                Text(text = label)
            }
        }

        GoogleMapsView(
            state = mapViewState,
            onMapClick = { point ->
                println("Clicked: ${point.latitude}, ${point.longitude}")
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
        ) {
            state.markers.forEach { sample ->
                val point = GeoPointImpl.fromLatLong(sample.lat, sample.lon)
                val icon = when (sample.provider) {
                    "gps" -> DefaultIcon(fillColor = Color.Blue, scale = 0.5f, label = "G")
                    "dead_reckoning" -> DefaultIcon(fillColor = Color.Red, scale = 0.5f, label = "D")
                    else -> DefaultIcon(fillColor = Color.Blue, scale = 0.5f, label = sample.provider)
                }
                Marker(
                    position = point,
                    icon = icon
                )
            }
        }
    }
}
