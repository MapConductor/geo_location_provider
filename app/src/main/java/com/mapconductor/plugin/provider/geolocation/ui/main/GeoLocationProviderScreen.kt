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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.ui.history.HistoryViewModel
import com.mapconductor.plugin.provider.geolocation.ui.history.LocationHistoryList

/**
 * 画面エントリ。
 * - 設定行は [Interval][DR予測間隔(秒)][Save&Apply] の順（2つの入力枠は同幅）
 * - 履歴は VM から受け取って描画コンポーネントに records として渡す（UI側で Room に触れない）
 */
@Composable
fun GeoLocationProviderScreen(
    state: UiState,
    onButtonClick: () -> Unit,
//    onManualExportClick: () -> Unit
) {
    val ctx = LocalContext.current
    val intervalVm: IntervalSettingsViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return IntervalSettingsViewModel(ctx) as T
            }
        }
    )
    val historyVm: HistoryViewModel = viewModel()
    val records by historyVm.latest.collectAsState(initial = emptyList())

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
//                HeaderArea(state)

                IntervalAndDrArea(intervalVm, ctx)

                HorizontalDivider(
                    thickness = DividerDefaults.Thickness,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // 履歴（UI側は Room に触れない設計）
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LocationHistoryList(records = records, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** [Interval][DR予測間隔(秒)][Save&Apply] + 2つの枠は同幅(weight=1f) */
@Composable
private fun HeaderArea(state: UiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = state.title, style = MaterialTheme.typography.titleLarge)
        Text(text = state.subtitle, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(
            thickness = DividerDefaults.Thickness,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun IntervalAndDrArea(
    vm: IntervalSettingsViewModel,
    ctx: Context
) {
    val sec by vm.secondsText.collectAsState()
    val dr by vm.drIntervalText.collectAsState()

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

        OutlinedTextField(
            value = sec,
            onValueChange = { vm.onSecondsChanged(it.filter { c -> c.isDigit() }.take(3)) },
            label = { Text("GPS取得間隔") },
//            supportingText = { Text("最小5秒") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = dr,
            onValueChange = { vm.onDrIntervalChanged(it.filter { c -> c.isDigit() }.take(3)) },
            label = { Text("DR予測間隔(秒)") },
//            supportingText = { Text("最小5秒、上限はGPS間隔の半分（切り捨て）。GPS間隔の最小は5秒です。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(onClick = { vm.saveAndApply() }) {
            Text("Save&Apply")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
}
