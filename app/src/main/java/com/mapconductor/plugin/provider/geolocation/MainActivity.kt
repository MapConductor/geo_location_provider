package com.mapconductor.plugin.provider.geolocation

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: GeoLocationProviderViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val uiState by vm.uiState.collectAsState()

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                GeoLocationProviderScreen(
                    state = uiState,
                    onButtonClick = { vm.onGeoLocationProviderClicked() },
                )
                Spacer(
                    Modifier.height(12.dp)
                )
                ServiceControlToggleWithPermission()
                Spacer(
                    Modifier.height(12.dp)
                )
                ServiceLocationReadout()
            }
        }
    }
}

@Composable
fun ServiceControlToggleWithPermission() {
    val context = LocalContext.current
    var isOn by rememberSaveable { mutableStateOf(false) }

    // 権限リクエスト（結果でON/OFF確定）
    val permsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val hasLocation =
            (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                    (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true)

        val hasNotif = if (Build.VERSION.SDK_INT >= 33) {
            result[Manifest.permission.POST_NOTIFICATIONS] == true
        } else true

        if (hasLocation && hasNotif) {
            startGeoService(context)
            isOn = true
        } else {
            Toast.makeText(context, "必要な権限が許可されていません。", Toast.LENGTH_SHORT).show()
            isOn = false
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (isOn) "GeoLocationService: ON" else "GeoLocationService: OFF")
        Switch(
            checked = isOn,
            onCheckedChange = { turnOn ->
                if (turnOn) {
                    // 既に許可済みかチェック
                    val needs = buildList {
                        val hasFine = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val hasCoarse = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasFine && !hasCoarse) {
                            add(Manifest.permission.ACCESS_FINE_LOCATION) // 高精度不要なら COARSE でもOK
                        }
                        if (Build.VERSION.SDK_INT >= 33) {
                            val hasNotif = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasNotif) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    if (needs.isEmpty()) {
                        startGeoService(context)
                        isOn = true
                    } else {
                        permsLauncher.launch(needs.toTypedArray())
                        // 結果はコールバックで isOn を確定
                    }
                } else {
                    // OFF → サービス停止
                    context.stopService(Intent(context, GeoLocationService::class.java))
                    isOn = false
                }
            }
        )
    }
}

// 実起動処理（UIスレッドから呼ばれる）
private fun startGeoService(context: Context) {
    val intent = Intent(context, GeoLocationService::class.java)
    // ユーザー操作（このボタン押下）から呼ばれるので FGS の起動要件も満たす
    ContextCompat.startForegroundService(context, intent)
}
