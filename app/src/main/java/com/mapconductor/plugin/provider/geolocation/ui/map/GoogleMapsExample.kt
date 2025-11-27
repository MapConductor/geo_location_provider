package com.mapconductor.plugin.provider.geolocation.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.core.features.GeoPointImpl
import com.mapconductor.core.map.MapCameraPositionImpl
import com.mapconductor.core.marker.DefaultIcon
import com.mapconductor.core.marker.Marker
import com.mapconductor.googlemaps.GoogleMapsView
import com.mapconductor.googlemaps.rememberGoogleMapViewState

/**
 * MapConductor-based map screen.
 *
 * - Marker position: latest LocationSample in DB (GPS/DRどちらでも可)。
 * - Camera position: 常にそのマーカーを中心にする。
 * - ログが1件も無い場合のみ、東京駅を仮の位置として使用します。
 */
@Composable
fun GoogleMapsExample() {
    val vm: MapViewModel = viewModel()
    val location by vm.currentLocation.collectAsState()

    val point = location?.let { sample ->
        GeoPointImpl.fromLatLong(sample.lat, sample.lon)
    } ?: GeoPointImpl.fromLatLong(35.6812, 139.7671)

    val camera = MapCameraPositionImpl(
        position = point,
        zoom = 14.0
    )

    val mapViewState = rememberGoogleMapViewState(
        cameraPosition = camera
    )

    GoogleMapsView(
        state = mapViewState,
        onMapClick = { p ->
            println("Clicked: ${p.latitude}, ${p.longitude}")
        }
    ) {
        Marker(
            position = point,
            icon = DefaultIcon(label = "Current")
        )
    }
}

@Composable
fun MapScreen() {
    GoogleMapsExample()
}
