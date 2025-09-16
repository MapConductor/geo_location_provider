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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
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
//                UpdateIntervalControl()
                val context = LocalContext.current
                val viewModel = remember {
                    IntervalSettingsViewModel(context.applicationContext)
                }
                IntervalSettingsSection(viewModel)

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
@Composable
fun IntervalSettingsSection(viewModel: IntervalSettingsViewModel) {
    val secondsState = viewModel.secondsText.collectAsState()
    val seconds = secondsState.value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = seconds,
            onValueChange = viewModel::onSecondsChanged,
            label = { Text("Update Interval (sec) · Min 5s") },
            singleLine = true,
            modifier = Modifier
                .weight(1f)      // TextField を可変幅に
        )
        Button(onClick = { viewModel.saveAndApply() }) {
            Text("Save & Apply")
        }
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
