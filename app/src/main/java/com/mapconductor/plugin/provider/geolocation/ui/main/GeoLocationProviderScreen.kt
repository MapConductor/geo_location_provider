package com.mapconductor.plugin.provider.geolocation.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mapconductor.plugin.provider.geolocation.ui.components.ExportButton
import com.mapconductor.plugin.provider.geolocation.ui.components.LocationHistoryList
import com.mapconductor.plugin.provider.geolocation.ui.components.ServiceLocationReadout
// ★ 統一：work パッケージの Scheduler を使用
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportScheduler

/**
 * 画面エントリ。
 * - トグル（起動/停止）
 * - 更新間隔の適用
 * - 現在の位置・バッテリー表示
 * - 履歴（最新30件）表示
 * - （P8）運用系：今すぐ実行／Drive設定へ
 */
@Composable
fun GeoLocationProviderScreen(
    state: UiState,
    onButtonClick: () -> Unit,
    // P8: Drive設定画面へ遷移するための任意コールバック（未配線でもビルド可能）
    onOpenDriveSettings: () -> Unit = {}
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(Modifier.height(64.dp))

                // 更新間隔（秒）入力 → サービスに反映
                val context = LocalContext.current
                val viewModel = remember {
                    IntervalSettingsViewModel(context.applicationContext)
                }
                IntervalSettingsSection(viewModel)

                Spacer(Modifier.height(8.dp))

                // 現在の位置・バッテリーの読み出し（bindで購読）
                ServiceLocationReadout()

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

//                // 主画面に出したい場合はコメントアウト解除
//                BacklogMaintenanceSection(
//                    onRunNow = { MidnightExportScheduler.runNow(context) },
//                    onOpenDriveSettings = onOpenDriveSettings
//                )

                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Latest 30 records",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                // ★ 履歴（LazyColumn）には残り領域を割り当てる
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    LocationHistoryList() // ← 中は LazyColumn のままでOK
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/* --- 以下、画面内で使う補助Composableたち --- */
@Composable
fun IntervalSettingsSection(viewModel: IntervalSettingsViewModel) {
    val secondsState = viewModel.secondsText.collectAsState()
    val seconds = secondsState.value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        OutlinedTextField(
            value = seconds,
            onValueChange = viewModel::onSecondsChanged,
            label = { Text("Update Interval(sec) *Min=5") },
            singleLine = true
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { viewModel.saveAndApply() }) {
            Text("Save & Apply")
        }
    }
}

/**
 * P8: 運用系の最小導線。
 * - 今すぐ実行（MidnightExportWorkerを即時enqueue）
 * - Drive設定へ（別画面遷移、未配線でもOK）
 */
@Composable
private fun BacklogMaintenanceSection(
    onRunNow: () -> Unit,
    onOpenDriveSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Maintenance",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onRunNow) { Text("今すぐ実行") }
            Button(onClick = onOpenDriveSettings) { Text("Drive設定へ") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "最古の未アップロード日を優先処理します（ネットワーク要件あり）。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
