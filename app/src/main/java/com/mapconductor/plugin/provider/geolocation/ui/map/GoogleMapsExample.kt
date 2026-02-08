package com.mapconductor.plugin.provider.geolocation.ui.map

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.core.circle.Circle
import com.mapconductor.core.features.GeoPointImpl
import com.mapconductor.core.map.MapCameraPositionImpl
import com.mapconductor.core.polyline.Polyline
import com.mapconductor.googlemaps.GoogleMapsView
import com.mapconductor.googlemaps.rememberGoogleMapViewState
import com.mapconductor.plugin.provider.geolocation.BuildConfig
import com.mapconductor.plugin.provider.geolocation.ui.common.Formatters
import com.mapconductor.plugin.provider.geolocation.ui.common.ProviderKind
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max

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
    val session by vm.mapSessionState.collectAsState()
    val context = LocalContext.current
    val isMapsApiKeyConfigured = remember(context) { isGoogleMapsApiKeyConfigured(context) }
    val signingCertSha1 = remember(context) { getSigningCertSha1(context) }

    LaunchedEffect(isMapsApiKeyConfigured) {
        if (!isMapsApiKeyConfigured) {
            Toast.makeText(
                context,
                "Google Maps API key is not configured. See README.md.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is MapViewModel.Event.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val gpsLatestSample = state.markers.firstOrNull { sample ->
        Formatters.providerKind(sample.provider) == ProviderKind.Gps
    }
    val gpsCorrectedLatestSample = state.markers.firstOrNull { sample ->
        Formatters.providerKind(sample.provider) == ProviderKind.GpsCorrected
    }
    val drLatestSample = state.markers.firstOrNull { sample ->
        Formatters.providerKind(sample.provider) == ProviderKind.DeadReckoning
    }

    val centerSample =
        when {
            state.gpsCorrectedChecked && gpsCorrectedLatestSample != null -> gpsCorrectedLatestSample
            state.gpsChecked && gpsLatestSample != null -> gpsLatestSample
            !state.gpsChecked && state.drChecked && drLatestSample != null -> drLatestSample
            gpsLatestSample != null -> gpsLatestSample
            drLatestSample != null -> drLatestSample
            else -> state.latest
        }

    val centerPoint = centerSample?.let { sample ->
        GeoPointImpl.fromLatLong(sample.lat, sample.lon)
    } ?: GeoPointImpl.fromLatLong(35.6812, 139.7671)

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val longEdgePx = with(density) {
        val w = configuration.screenWidthDp.dp.toPx()
        val h = configuration.screenHeightDp.dp.toPx()
        max(w, h)
    }

    val targetMeters = 1000.0
    val zoom: Double =
        if (longEdgePx > 0f) {
            val metersPerPixel = targetMeters / longEdgePx.toDouble()
            val latRad = Math.toRadians(centerPoint.latitude)
            val cosLat = cos(latRad).coerceAtLeast(0.01)
            val earthCircumference = 2.0 * PI * 6371000.0
            val twoToZoom =
                (cosLat * earthCircumference) / (256.0 * metersPerPixel)
            log2(twoToZoom).coerceIn(3.0, 21.0)
        } else {
            14.0
        }

    val camera = MapCameraPositionImpl(
        position = centerPoint,
        zoom = zoom
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
            CurveModeDropdown(
                selected = state.curveMode,
                onSelected = { vm.onCurveModeChange(it) },
                enabled = !state.filterApplied,
                modifier = Modifier.weight(1f)
            )

            PointSelectionModeDropdown(
                selected = state.pointSelectionMode,
                onSelected = { vm.onPointSelectionModeChange(it) },
                enabled = !state.filterApplied,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = state.gpsChecked,
                onCheckedChange = { vm.onGpsCheckedChange(it) },
                enabled = !state.filterApplied
            )
            Text(text = "GPS")

            Checkbox(
                checked = state.gpsCorrectedChecked,
                onCheckedChange = { vm.onGpsCorrectedCheckedChange(it) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !state.filterApplied
            )
            Text(text = "GPS(EKF)")

            Checkbox(
                checked = state.gpsDrChecked,
                onCheckedChange = { vm.onGpsDrCheckedChange(it) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !state.filterApplied
            )
            Text(text = "GPS&DR")

            Checkbox(
                checked = state.drChecked,
                onCheckedChange = { vm.onDrCheckedChange(it) },
                modifier = Modifier.padding(start = 8.dp),
                enabled = !state.filterApplied,
            )
            Text(text = "Dead Reckoning")
        }

        OutlinedTextField(
            value = state.rangeText,
            onValueChange = { vm.onRangeChanged(it) },
            label = { Text("Range (optional)") },
            placeholder = {
                Text("yyyy/MM/dd[-yyyy/MM/dd], yyyy/MM/dd_HH:mm:ss[-...], or n-m")
            },
            singleLine = true,
            enabled = !state.filterApplied,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.limitText,
                onValueChange = { vm.onLimitChanged(it) },
                label = { Text("Count (0 = all)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !state.filterApplied,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { vm.onApplyClicked() }
            ) {
                val label = if (state.filterApplied) "Cancel" else "Apply"
                Text(text = label)
            }
        }

        key(session) {
            val mapViewState = rememberGoogleMapViewState(
                cameraPosition = camera
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp)
            ) {
                GoogleMapsView(
                    state = mapViewState,
                    onMapClick = { point ->
                        println("Clicked: ${point.latitude}, ${point.longitude}")
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Build per-provider polylines, connecting points in time order.
                    val gpsCorrectedSamples = state.markers
                        .filter { sample ->
                            Formatters.providerKind(sample.provider) == ProviderKind.GpsCorrected
                        }
                        .sortedBy { it.timeMillis }

                    val gpsSamples = state.markers
                        .filter { sample ->
                            Formatters.providerKind(sample.provider) == ProviderKind.Gps
                        }
                        .sortedBy { it.timeMillis }

                    val drSamples = state.markers
                        .filter { sample ->
                            Formatters.providerKind(sample.provider) == ProviderKind.DeadReckoning
                        }
                        .sortedBy { it.timeMillis }

                    val gpsCorrectedPoints =
                        if (state.curveMode == MapCurveMode.GAP_AWARE_BEZIER) {
                            GapAwareBezierPolyline.build(gpsCorrectedSamples)
                        } else {
                            val basePoints = gpsCorrectedSamples.map { sample ->
                                GeoPointImpl.fromLatLong(sample.lat, sample.lon)
                            }
                            applyCurveMode(basePoints, state.curveMode)
                        }

                    val gpsPoints =
                        if (state.curveMode == MapCurveMode.GAP_AWARE_BEZIER) {
                            GapAwareBezierPolyline.build(gpsSamples)
                        } else {
                            val basePoints = gpsSamples.map { sample ->
                                GeoPointImpl.fromLatLong(sample.lat, sample.lon)
                            }
                            applyCurveMode(basePoints, state.curveMode)
                        }

                    val drPoints =
                        if (state.curveMode == MapCurveMode.GAP_AWARE_BEZIER) {
                            GapAwareBezierPolyline.build(drSamples)
                        } else {
                            val basePoints = drSamples.map { sample ->
                                GeoPointImpl.fromLatLong(sample.lat, sample.lon)
                            }
                            applyCurveMode(basePoints, state.curveMode)
                        }

                    // Draw corrected GPS polyline first (backmost).
                    if (state.gpsCorrectedChecked && gpsCorrectedPoints.size >= 2) {
                        Polyline(
                            points = gpsCorrectedPoints,
                            id = "gps-corrected-polyline",
                            strokeColor = Color(0xFFFF00FF),
                            strokeWidth = 8.dp
                        )
                    }

                    // Draw GPS polyline first (behind).
                    if (state.gpsChecked && gpsPoints.size >= 2) {
                        Polyline(
                            points = gpsPoints,
                            id = "gps-polyline",
                            strokeColor = Color.Blue,
                            strokeWidth = 6.dp
                        )
                    }

                    // Draw GPS&DR mixed polyline (green) next.
                    if (state.gpsDrChecked && state.gpsDrPath.size >= 2) {
                        val mixedPoints =
                            if (state.curveMode == MapCurveMode.GAP_AWARE_BEZIER) {
                                GapAwareBezierPolyline.build(state.gpsDrPath)
                            } else {
                                val basePoints = state.gpsDrPath.map { sample ->
                                    GeoPointImpl.fromLatLong(sample.lat, sample.lon)
                                }
                                applyCurveMode(basePoints, state.curveMode)
                            }

                        if (mixedPoints.size >= 2) {
                            Polyline(
                                points = mixedPoints,
                                id = "gpsdr-polyline",
                                strokeColor = Color.Green,
                                strokeWidth = 3.dp
                            )
                        }
                    }

                    // Draw DR polyline last (in front).
                    if (state.drChecked && drPoints.size >= 2) {
                        Polyline(
                            points = drPoints,
                            id = "dr-polyline",
                            strokeColor = Color.Red,
                            strokeWidth = 6.dp
                        )
                    }

                    // Draw accuracy circle for the latest GPS sample, if available.
                    val latestGpsSample = state.markers.firstOrNull { sample ->
                        Formatters.providerKind(sample.provider) == ProviderKind.Gps
                    }
                    if (latestGpsSample != null && latestGpsSample.accuracy > 0f) {
                        val center = GeoPointImpl.fromLatLong(latestGpsSample.lat, latestGpsSample.lon)
                        Circle(
                            center = center,
                            radius = latestGpsSample.accuracy.toDouble(),
                            id = "gps-accuracy-circle",
                            strokeColor = Color.Blue,
                            strokeWidth = 0.5.dp,
                            fillColor = Color.Blue.copy(alpha = 0.2f)
                        )
                    }
                }

                if (!isMapsApiKeyConfigured) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                            .background(Color(0xCC000000))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Google Maps API key is not configured.",
                            color = Color.White
                        )
                        Text(
                            text = "Create secrets.properties in the project root and set GOOGLE_MAPS_API_KEY. If you have secret.properties, rename it to secrets.properties.",
                            color = Color.White
                        )
                        Text(
                            text = "See README.md for details.",
                            color = Color.White
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0x66000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "GPS : ${state.displayedGpsCount} / ${state.dbGpsCount}",
                        color = Color.White
                    )
                    Text(
                        text = "GPSC: ${state.displayedGpsCorrectedCount} / ${state.dbGpsCorrectedCount}",
                        color = Color.White
                    )
                    Text(
                        text = "DR  : ${state.displayedDrCount} / ${state.dbDrCount}",
                        color = Color.White
                    )
                    Text(
                        text = "ALL : ${state.displayedTotalCount} / ${state.dbTotalCount}",
                        color = Color.White
                    )
                    val staticLabel = if (state.debugIsStatic) "YES" else "NO"
                    Text(
                        text = "Static: $staticLabel",
                        color = Color.White
                    )
                    val drDist = state.debugDrToGpsDistanceM
                    if (drDist != null) {
                        Text(
                            text = "DR-GPS: ${"%.1f".format(drDist)} m",
                            color = Color.White
                        )
                    }
                    val acc = state.debugLatestGpsAccuracyM
                    if (acc != null && acc > 0f) {
                        Text(
                            text = "GPS acc: ${"%.1f".format(acc)} m",
                            color = Color.White
                        )
                    }
                    val gpsScale = state.debugGpsInfluenceScale
                    if (gpsScale != null) {
                        Text(
                            text = "GPS weight: ${"%.2f".format(gpsScale)}",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun isGoogleMapsApiKeyConfigured(context: android.content.Context): Boolean {
    val key = runCatching {
        val ai = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        ai.metaData?.getString("com.google.android.geo.API_KEY")
    }.getOrNull().orEmpty().trim()

    if (key.isBlank()) return false
    if (key == "YOUR_GOOGLE_MAPS_API_KEY") return false
    if (key.contains("GOOGLE_MAPS_API_KEY")) return false
    if (key.contains("\${")) return false
    return true
}

private fun getSigningCertSha1(context: Context): String? {
    return runCatching {
        val pm = context.packageManager
        val pkg = context.packageName

        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            }

        val signatures =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

        val signatureBytes = signatures?.firstOrNull()?.toByteArray() ?: return null
        val digest = MessageDigest.getInstance("SHA1").digest(signatureBytes)
        digest.joinToString(separator = ":") { b -> "%02X".format(b) }
    }.getOrNull()
}

@Composable
private fun CurveModeDropdown(
    selected: MapCurveMode,
    onSelected: (MapCurveMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val expanded = remember { mutableStateOf(false) }
    val label = "Curve"
    val text =
        when (selected) {
            MapCurveMode.LINEAR -> "Linear"
            MapCurveMode.GAP_AWARE_BEZIER -> "Gap-aware (Bezier)"
            MapCurveMode.BEZIER -> "Bezier"
            MapCurveMode.SPLINE -> "Spline"
            MapCurveMode.MOVING_AVERAGE_LINEAR -> "Moving average (linear)"
            MapCurveMode.MOVING_AVERAGE_BEZIER -> "Moving average (Bezier)"
            MapCurveMode.MOVING_AVERAGE_SPLINE -> "Moving average (Spline)"
            MapCurveMode.CORNER_CUTTING_1 -> "corner-cutting[1]"
            MapCurveMode.CORNER_CUTTING_2 -> "corner-cutting[2]"
            MapCurveMode.CORNER_CUTTING_3 -> "corner-cutting[3]"
        }

    val contentAlpha = if (enabled) 1f else 0.5f

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha)
                .clickable(enabled = enabled) {
                    if (enabled) {
                        expanded.value = true
                    }
                }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label)
            Text(
                text = ": $text",
                modifier = Modifier
                    .padding(start = 4.dp)
                    .weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "v",
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        DropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false }
        ) {
            MapCurveMode.values().forEach { mode ->
                val optionText =
                    when (mode) {
                        MapCurveMode.LINEAR -> "Linear"
                        MapCurveMode.GAP_AWARE_BEZIER -> "Gap-aware (Bezier)"
                        MapCurveMode.BEZIER -> "Bezier"
                        MapCurveMode.SPLINE -> "Spline"
                        MapCurveMode.MOVING_AVERAGE_LINEAR -> "Moving average (linear)"
                        MapCurveMode.MOVING_AVERAGE_BEZIER -> "Moving average (Bezier)"
                        MapCurveMode.MOVING_AVERAGE_SPLINE -> "Moving average (Spline)"
                        MapCurveMode.CORNER_CUTTING_1 -> "corner-cutting[1]"
                        MapCurveMode.CORNER_CUTTING_2 -> "corner-cutting[2]"
                        MapCurveMode.CORNER_CUTTING_3 -> "corner-cutting[3]"
                    }
                DropdownMenuItem(
                    text = { Text(optionText) },
                    onClick = {
                        expanded.value = false
                        onSelected(mode)
                    }
                )
            }
        }
    }
}

@Composable
private fun PointSelectionModeDropdown(
    selected: MapPointSelectionMode,
    onSelected: (MapPointSelectionMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val expanded = remember { mutableStateOf(false) }
    val label = "Point selection"
    val text =
        when (selected) {
            MapPointSelectionMode.TIME_PRIORITY -> "Time"
            MapPointSelectionMode.DISTANCE_PRIORITY -> "Distance"
        }

    val contentAlpha = if (enabled) 1f else 0.5f

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha)
                .clickable(enabled = enabled) {
                    if (enabled) {
                        expanded.value = true
                    }
                }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label)
            Text(
                text = ": $text",
                modifier = Modifier
                    .padding(start = 4.dp)
                    .weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "v",
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        DropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false }
        ) {
            MapPointSelectionMode.values().forEach { mode ->
                val optionText =
                    when (mode) {
                        MapPointSelectionMode.TIME_PRIORITY -> "Time priority"
                        MapPointSelectionMode.DISTANCE_PRIORITY -> "Distance priority"
                    }
                DropdownMenuItem(
                    text = { Text(optionText) },
                    onClick = {
                        expanded.value = false
                        onSelected(mode)
                    }
                )
            }
        }
    }
}

private fun buildMovingAverage3Polyline(
    points: List<GeoPointImpl>
): List<GeoPointImpl> {
    if (points.size < 3) {
        return points
    }

    val result = mutableListOf<GeoPointImpl>()
    result.add(points.first())

    for (i in 0 until points.size - 2) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val p2 = points[i + 2]

        val lat = (p0.latitude + p1.latitude + p2.latitude) / 3.0
        val lon = (p0.longitude + p1.longitude + p2.longitude) / 3.0

        result.add(GeoPointImpl.fromLatLong(lat, lon))
    }

    result.add(points.last())
    return result
}

private fun buildBezierPolyline(points: List<GeoPointImpl>): List<GeoPointImpl> {
    if (points.size < 2) {
        return points
    }

    val result = mutableListOf<GeoPointImpl>()
    result.add(points.first())

    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]

        val qLat = p0.latitude * 0.75 + p1.latitude * 0.25
        val qLon = p0.longitude * 0.75 + p1.longitude * 0.25
        val rLat = p0.latitude * 0.25 + p1.latitude * 0.75
        val rLon = p0.longitude * 0.25 + p1.longitude * 0.75

        result.add(GeoPointImpl.fromLatLong(qLat, qLon))
        result.add(GeoPointImpl.fromLatLong(rLat, rLon))
    }

    result.add(points.last())
    return result
}

private fun buildSplinePolyline(points: List<GeoPointImpl>): List<GeoPointImpl> {
    if (points.size < 2) {
        return points
    }
    if (points.size == 2) {
        return points
    }

    val result = mutableListOf<GeoPointImpl>()
    val stepsPerSegment = 6

    for (i in 0 until points.size - 1) {
        val p0 = if (i == 0) points[i] else points[i - 1]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = if (i + 2 < points.size) points[i + 2] else points[i + 1]

        for (step in 0 until stepsPerSegment) {
            val t = step.toDouble() / stepsPerSegment.toDouble()
            val lat = catmullRom(p0.latitude, p1.latitude, p2.latitude, p3.latitude, t)
            val lon = catmullRom(p0.longitude, p1.longitude, p2.longitude, p3.longitude, t)
            result.add(GeoPointImpl.fromLatLong(lat, lon))
        }
    }

    result.add(points.last())
    return result
}

private fun catmullRom(
    p0: Double,
    p1: Double,
    p2: Double,
    p3: Double,
    t: Double
): Double {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5 *
        (
            (2.0 * p1) +
                (-p0 + p2) * t +
                (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
                (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
        )
}

private fun buildChaikinPolyline(
    points: List<GeoPointImpl>,
    iterations: Int
): List<GeoPointImpl> {
    if (points.size < 2) {
        return points
    }
    val clampedIterations = iterations.coerceIn(1, 5)
    var current = points

    repeat(clampedIterations) {
        if (current.size < 2) {
            return current
        }
        val next = mutableListOf<GeoPointImpl>()
        next.add(current.first())

        for (i in 0 until current.size - 1) {
            val p0 = current[i]
            val p1 = current[i + 1]

            val qLat = p0.latitude * 0.75 + p1.latitude * 0.25
            val qLon = p0.longitude * 0.75 + p1.longitude * 0.25
            val rLat = p0.latitude * 0.25 + p1.latitude * 0.75
            val rLon = p0.longitude * 0.25 + p1.longitude * 0.75

            next.add(GeoPointImpl.fromLatLong(qLat, qLon))
            next.add(GeoPointImpl.fromLatLong(rLat, rLon))
        }

        next.add(current.last())
        current = next
    }

    return current
}

private fun applyCurveMode(
    basePoints: List<GeoPointImpl>,
    mode: MapCurveMode
): List<GeoPointImpl> {
    return when (mode) {
        MapCurveMode.LINEAR -> basePoints
        MapCurveMode.GAP_AWARE_BEZIER -> basePoints
        MapCurveMode.BEZIER -> buildBezierPolyline(basePoints)
        MapCurveMode.SPLINE -> buildSplinePolyline(basePoints)
        MapCurveMode.MOVING_AVERAGE_LINEAR -> buildMovingAverage3Polyline(basePoints)
        MapCurveMode.MOVING_AVERAGE_BEZIER -> {
            val filtered = buildMovingAverage3Polyline(basePoints)
            buildBezierPolyline(filtered)
        }
        MapCurveMode.MOVING_AVERAGE_SPLINE -> {
            val filtered = buildMovingAverage3Polyline(basePoints)
            buildSplinePolyline(filtered)
        }
        MapCurveMode.CORNER_CUTTING_1 -> buildChaikinPolyline(basePoints, iterations = 1)
        MapCurveMode.CORNER_CUTTING_2 -> buildChaikinPolyline(basePoints, iterations = 2)
        MapCurveMode.CORNER_CUTTING_3 -> buildChaikinPolyline(basePoints, iterations = 3)
    }
}
