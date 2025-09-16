package com.mapconductor.plugin.provider.geolocation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
/**
 * 画面エントリ。
 * - トグル（起動/停止）
 * - 更新間隔の適用
 * - 現在の位置・バッテリー表示
 * - 履歴（最新30件）表示
 */
@Composable
fun GeoLocationProviderScreen(
    state: UiState,
    onButtonClick: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(), // ← verticalScroll() を外す
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(Modifier.height(64.dp))

                // 更新間隔（秒）入力 → サービスに反映
                UpdateIntervalControl()

                Spacer(Modifier.height(8.dp))

                // 現在の位置・バッテリーの読み出し（bindで購読）
                ServiceLocationReadout()

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                Spacer(Modifier.height(2.dp))
                ExportButton(limit = null)
                Spacer(Modifier.height(2.dp))
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                Spacer(Modifier.height(8.dp))


                Text(
                    text = "Latest 30 records",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                // ★ 履歴（LazyColumn）には残り領域を割り当てる
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)   // ← これがポイント
                ) {
                    LocationHistoryList() // ← 中は LazyColumn のままでOK
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/* --- 以下、画面内で使う補助Composableたち --- */

/** 権限付き 起動/停止トグル（UIと実体を GeoLocationService.running で同期） */
@Composable
fun ServiceControlToggleWithPermission() {
    val context = LocalContext.current
    val running by GeoLocationService.running.collectAsState()
    val scope = rememberCoroutineScope()

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
            ContextCompat.startForegroundService(
                context, Intent(context, GeoLocationService::class.java)
            )
        } else {
            Toast.makeText(context, "必要な権限が許可されていません。", Toast.LENGTH_SHORT).show()
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (running) "GeoLocationService: ON" else "GeoLocationService: OFF")
        Switch(
            checked = running,
            onCheckedChange = { turnOn ->
                if (turnOn) {
                    val needs = buildList {
                        val hasFine = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val hasCoarse = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasFine && !hasCoarse) add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) {
                            val hasNotif = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasNotif) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (needs.isEmpty()) {
                        ContextCompat.startForegroundService(
                            context, Intent(context, GeoLocationService::class.java)
                        )
                    } else {
                        permsLauncher.launch(needs.toTypedArray())
                    }
                } else {
                    context.stopService(Intent(context, GeoLocationService::class.java))
                }
            }
        )
    }
}

/** 更新間隔（秒）を入力してサービスに反映 */
@Composable
fun UpdateIntervalControl() {
    val context = LocalContext.current
    val running by GeoLocationService.running.collectAsState()
    var text by rememberSaveable { mutableStateOf("5") } // 既定 5秒
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() } },
            label = { Text("更新間隔(秒) ※最短5秒") },
            singleLine = true
        )
        Button(
            onClick = {
                val sec = text.toLongOrNull() ?: 5L
                val ms = sec.coerceIn(1L, 3600L) * 1000L
                // 既にサービスが動いている前提で、間隔更新アクションを送る
                val intent = Intent(context, GeoLocationService::class.java).apply {
                    action = GeoLocationService.ACTION_UPDATE_INTERVAL
                    putExtra(GeoLocationService.EXTRA_UPDATE_MS, ms)
                }
                // 起動中なら startService で onStartCommand が呼ばれ、間隔が更新される
                context.startService(intent)
                Toast.makeText(context, "更新間隔を ${sec}s に適用", Toast.LENGTH_SHORT).show()
            },
            enabled = running
        ) { Text("適用") }
    }
}
@Composable
fun ExportButton(
    modifier: Modifier = Modifier,
    limit: Int? = 1000 // null なら全件
) {
    val context = LocalContext.current
    val vm: ManualExportViewModel = viewModel(factory = ManualExportViewModel.factory(context))

    Button(onClick = { vm.exportAll(limit) }, modifier = modifier) {
        Text("Export to Downloads")
    }
}