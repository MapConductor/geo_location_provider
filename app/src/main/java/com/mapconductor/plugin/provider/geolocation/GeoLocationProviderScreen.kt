package com.mapconductor.plugin.provider.geolocation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GeoLocationProviderScreen(
    state: UiState,
    onButtonClick: () -> Unit,
) {
    androidx.compose.material3.MaterialTheme {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                // 既存の中央ボタン
                androidx.compose.material3.Button(
                    onClick = onButtonClick,
                    modifier = androidx.compose.ui.Modifier.padding(16.dp)
                ) { androidx.compose.material3.Text(state.buttonText) }

                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(12.dp)
                )

                // 既存の「権限付きスタート/ストップ」ボタン
                ServiceControlButtonsWithPermission()

                androidx.compose.foundation.layout.Spacer(
                    androidx.compose.ui.Modifier.height(12.dp)
                )

                // ← 位置表示をここで呼ぶ
                ServiceLocationReadout()
            }
        }
    }
}