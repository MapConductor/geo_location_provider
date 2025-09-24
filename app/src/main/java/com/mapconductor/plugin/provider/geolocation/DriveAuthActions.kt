package com.mapconductor.plugin.provider.geolocation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import kotlinx.coroutines.launch

@Composable
fun DriveAuthActions() {
    val ctx = LocalContext.current
    // applicationContext を渡してリーク回避
    val repo = remember { GoogleAuthRepository(ctx.applicationContext) }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        scope.launch {
            val acct = repo.handleSignInResult(res.data)
            status = acct?.email?.let { "Signed in: $it" } ?: "Sign-in failed"
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { launcher.launch(repo.buildSignInIntent()) }) {
            Text("Sign in")
        }
        Button(onClick = {
            scope.launch {
                val token = repo.getAccessTokenOrNull()
                status = token?.let { "Token OK: ${it.take(12)}…" } ?: "Token failed"
            }
        }) {
            Text("Get Token")
        }
    }

    // ステータス表示（必要なら場所に合わせて移動）
    androidx.compose.material3.Text(text = status)
}
