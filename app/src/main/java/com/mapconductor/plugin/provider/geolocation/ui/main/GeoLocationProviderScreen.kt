package com.mapconductor.plugin.provider.geolocation.ui.main

import android.content.Context
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
import com.mapconductor.plugin.provider.geolocation.ui.components.LocationHistoryList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ★ 統一：work パッケージの Scheduler を使用

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
                Spacer(Modifier.height(12.dp))

                // 更新間隔（秒）入力 → サービスに反映
                val context = LocalContext.current
                val viewModel = remember {
                    LocalIntervalSettingsViewModel(context.applicationContext)
                }
                IntervalSettingsSection(viewModel)

                Spacer(Modifier.height(8.dp))

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

                // 主画面に出したい場合はコメントアウト解除
                // BacklogMaintenanceSection(
                //     onRunNow = { MidnightExportScheduler.runNow(context) },
                //     onOpenDriveSettings = onOpenDriveSettings
                // )

                HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                Spacer(Modifier.height(8.dp))

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
fun IntervalSettingsSection(viewModel: LocalIntervalSettingsViewModel) {
    val seconds = viewModel.secondsText.collectAsState().value

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
        Spacer(Modifier.height(8.dp))

        val predictCount = viewModel.predictCount.collectAsState().value
        OutlinedTextField(
            value = predictCount,
            onValueChange = viewModel::onPredictCountChanged,
            label = { Text("予測回数") },
            singleLine = true,
            enabled = true, // IMU非搭載でも入力は可能。ただし保存時の検証で拒否（仕様どおり）
            modifier = Modifier.fillMaxWidth()
        )
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
        Spacer(Modifier.height(4.dp))
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

/* --- 画面内ローカルVM（同名衝突を避けるためリネーム） --- */
class LocalIntervalSettingsViewModel(
    @Suppress("unused") private val appContext: Context
) {
    // 表示用 StateFlow（collectAsState() に初期値不要）
    private val _secondsText = MutableStateFlow("10") // 既定 10sec
    val secondsText: StateFlow<String> = _secondsText

    private val _predictCount = MutableStateFlow("5") // 既定 5回
    val predictCount: StateFlow<String> = _predictCount

    fun onSecondsChanged(newValue: String) {
        // 数字以外は無視、空は許容
        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
            _secondsText.value = newValue
        }
    }

    fun onPredictCountChanged(newValue: String) {
        if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
            _predictCount.value = newValue
        }
    }

    /** 仕様の最小値（秒）や回数の簡易検証のみ。永続化/サービス反映は既存の実装に接続してください。 */
    fun saveAndApply() {
        // 最低5秒（空や0は5に矯正）
        val sec = _secondsText.value.toIntOrNull()?.coerceAtLeast(5) ?: 5
        _secondsText.value = sec.toString()

        // 予測回数は1以上（空や0は1に矯正）
        val count = _predictCount.value.toIntOrNull()?.coerceAtLeast(1) ?: 1
        _predictCount.value = count.toString()

        // TODO: 永続化（DataStore 等）と、サービスへの反映（既存の設定適用フロー）を呼び出す
        // ex) settingsStore.saveIntervalSeconds(sec)
        // ex) settingsStore.savePredictCount(count)
        // ex) ForegroundService.applyNewSettings(...)
    }
}
