package com.mapconductor.plugin.provider.geolocation

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: GeoLocationProviderViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val uiState by vm.uiState.collectAsState()

            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                GeoLocationProviderScreen(
                    state = uiState,
                    onButtonClick = { vm.onGeoLocationProviderClicked() },
                )
                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(12.dp)
                )
                ServiceControlButtonsWithPermission()
                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(12.dp)
                )
                ServiceLocationReadout()
            }
        }
    }
}

@Composable
fun ServiceControlButtonsWithPermission() {
    val context = LocalContext.current

    // 権限リクエストのランチャー（結果コールバック内で許可判定→サービス起動）
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
        } else {
            Toast.makeText(context, "必要な権限が許可されていません。", Toast.LENGTH_SHORT).show()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = {
            // 1) 既に許可済みかチェック
            val needs = mutableListOf<String>()

            val hasFine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                // どちらかでOK。高精度が不要なら COARSE にしても良い
                needs += Manifest.permission.ACCESS_FINE_LOCATION
            }

            if (Build.VERSION.SDK_INT >= 33) {
                val hasNotif = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasNotif) needs += Manifest.permission.POST_NOTIFICATIONS
            }

            // 2) 未許可がなければ即起動、あれば権限リクエスト
            if (needs.isEmpty()) {
                startGeoService(context)
            } else {
                permsLauncher.launch(needs.toTypedArray())
            }
        }) {
            Text("Start GeoLocationService")
        }

        Spacer(Modifier.height(12.dp))

        Button(onClick = {
            val intent = Intent(context, GeoLocationService::class.java)
            context.stopService(intent)
        }) {
            Text("Stop GeoLocationService")
        }
    }
}

// 実起動処理（UIスレッドから呼ばれる）
private fun startGeoService(context: Context) {
    val intent = Intent(context, GeoLocationService::class.java)
    // ユーザー操作（このボタン押下）から呼ばれるので FGS の起動要件も満たす
    ContextCompat.startForegroundService(context, intent)
}

//@Composable
//fun ServiceControlButtons() {
//    val context = LocalContext.current
//
//    Column(horizontalAlignment = Alignment.CenterHorizontally) {
//        Button(onClick = {
//            val intent = Intent(context, GeoLocationService::class.java)
//            // Android 8.0+ は startForegroundService が必要
//            ContextCompat.startForegroundService(context, intent)
//        }) {
//            Text("Start GeoLocationService")
//        }
//
//        Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
//
//        Button(onClick = {
//            val intent = Intent(context, GeoLocationService::class.java)
//            context.stopService(intent)
//        }) {
//            Text("Stop GeoLocationService")
//        }
//    }
//}
