package com.mapconductor.plugin.provider.geolocation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ExperimentalMaterial3Api

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(
                    onStartTracking = { requestPermissionsAndStartService() },
                    onStopTracking  = { stopLocationService() }
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
        }
    }

    override fun onStart() {
        super.onStart()
        // 既に権限が揃っているなら”自動開始”
        val locOk =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

        if (locOk && notifOk) {
            startLocationService()           // ← これで起動直後に自動開始
        }
        // ※ 初回から自動で許可ダイアログも出したいなら、下の行に置き換え可
        // else requestPermissionsAndStartService()
    }

    private fun startLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_START) // ACTION_START未定義ならこの行を削除
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_STOP) // 未定義なら setAction を削除
        stopService(intent)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val locOk = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notifOk = if (Build.VERSION.SDK_INT >= 33)
            (granted[Manifest.permission.POST_NOTIFICATIONS] == true) else true
        if (locOk && notifOk) startLocationService()
    }

    private fun requestPermissionsAndStartService() {
        val need = mutableListOf<String>()
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) startLocationService() else permLauncher.launch(need.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GeoLocation") },
                actions = {
                    IconButton(onClick = onStartTracking) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Start")
                    }
                    // Stop は TextButton にして icons-extended 依存を回避
                    TextButton(onClick = onStopTracking) {
                        Text("Stop")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onStartTracking,
                text = { Text("開始") },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
            )
        }
    ) { inner ->
        androidx.compose.foundation.layout.Box(Modifier.padding(inner)) {
            content()
        }
    }
}
