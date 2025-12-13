package com.mapconductor.plugin.provider.geolocation.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mapconductor.core.features.GeoPointImpl
import com.mapconductor.core.map.MapCameraPositionImpl
import com.mapconductor.core.marker.DefaultIcon
import com.mapconductor.core.marker.Marker
import com.mapconductor.googlemaps.GoogleMapsView
import com.mapconductor.googlemaps.rememberGoogleMapViewState
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import com.mapconductor.plugin.provider.geolocation.ui.components.ServiceToggleAction
import com.mapconductor.plugin.provider.geolocation.ui.map.MapScreen
import com.mapconductor.plugin.provider.geolocation.ui.pickup.PickupScreen
import com.mapconductor.plugin.provider.geolocation.ui.settings.DriveSettingsScreen
import com.mapconductor.plugin.provider.geolocation.ui.settings.UploadSettingsScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_PICKUP = "pickup"
private const val ROUTE_MAP = "map"
private const val ROUTE_UPLOAD_SETTINGS = "upload_settings"

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val locOk = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notifOk = if (Build.VERSION.SDK_INT >= 33) {
            granted[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true
        }
        if (locOk && notifOk) startLocationService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_HOME
                ) {
                    composable(ROUTE_HOME) {
                        AppRoot(
                            onStartTracking = { requestPermissionsAndStartService() },
                            onStopTracking = { stopLocationService() },
                            onOpenDriveSettings = { navController.navigate("drive_settings") },
                            onOpenUploadSettings = { navController.navigate(ROUTE_UPLOAD_SETTINGS) }
                        ) {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                val vm: GeoLocationProviderViewModel = viewModel()
                                GeoLocationProviderScreen(
                                    state = vm.uiState.value,
                                    onButtonClick = { vm.onGeoLocationProviderClicked() }
                                )
                            }
                        }
                    }
                    composable("drive_settings") {
                        DriveSettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(ROUTE_UPLOAD_SETTINGS) {
                        UploadSettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val locOk =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        // At the moment we only check permissions here; behavior is not changed.
        // You can add UI notifications using locOk / notifOk if needed.
    }

    private fun startLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_STOP)
        startService(intent)
    }

    private fun requestPermissionsAndStartService() {
        val need = mutableListOf<String>()
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) {
            startLocationService()
        } else {
            permLauncher.launch(need.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onOpenDriveSettings: () -> Unit,
    onOpenUploadSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (currentRoute) {
                        ROUTE_PICKUP -> "Pickup"
                        ROUTE_MAP -> "Map"
                        else -> "GeoLocation"
                    }
                    Text(title)
                },
                navigationIcon = {
                    if (currentRoute == ROUTE_PICKUP || currentRoute == ROUTE_MAP) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    // On HOME: show Pickup / Drive / Start/Stop.
                    // On Pickup / Map screens: only show Back button.
                    if (currentRoute != ROUTE_PICKUP && currentRoute != ROUTE_MAP) {
                        TextButton(onClick = {
                            navController.navigate(ROUTE_MAP) { launchSingleTop = true }
                        }) { Text("Map") }

                        TextButton(onClick = {
                            navController.navigate(ROUTE_PICKUP) { launchSingleTop = true }
                        }) { Text("Pickup") }

                        TextButton(onClick = onOpenDriveSettings) { Text("Drive") }

                        TextButton(onClick = onOpenUploadSettings) { Text("Upload") }

                        ServiceToggleAction()
                    }
                }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            NavHost(navController = navController, startDestination = ROUTE_HOME) {
                composable(ROUTE_HOME) { content() }
                composable(ROUTE_PICKUP) {
                    PickupScreen()
                }
                composable(ROUTE_MAP) {
                    MapScreen()
                }
            }
        }
    }
}
