package com.mapconductor.plugin.provider.geolocation.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveSettingsScreen(
    vm: DriveSettingsViewModel = viewModel(),
    onBack: () -> Unit = {}   // ← ナビゲーションから戻れるように（未配線でもビルド可）
) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsState()
    val folderId by vm.folderId.collectAsState()
    val account by vm.accountEmail.collectAsState()
    val lastRefresh by vm.tokenLastRefresh.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res -> vm.onSignInResult(res.data) }

    val lastRefreshText = remember(lastRefresh) {
        if (lastRefresh > 0) {
            val dt = Instant.ofEpochMilli(lastRefresh)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            "Token refreshed: $dt"
        } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Drive 基本設定 ---
            OutlinedTextField(
                value = folderId,
                onValueChange = { vm.updateFolderId(it) },
                label = { Text("Folder ID") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(text = "Account: $account")
            if (lastRefreshText != null) Text(text = lastRefreshText)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { launcher.launch(vm.buildSignInIntent()) }) { Text("Sign in") }
                Button(onClick = { vm.getToken() }) { Text("Get Token") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.callAboutGet() }) { Text("About.get") }
                OutlinedButton(onClick = { vm.validateFolder() }) { Text("Validate Folder") }
            }

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            Text(text = status, style = MaterialTheme.typography.bodyMedium)

            // --- P8: 運用系（Backlogの見える化/操作） ---
            BacklogPanel()
        }
    }
}

@Composable
private fun BacklogPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var oldest by remember { mutableStateOf<Long?>(null) }
    var uiMsg by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) } // ← DataStoreからFlowで読ませてもOK

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).exportedDayDao()
            oldest = dao.oldestNotUploaded()?.epochDay
        }
        // ★任意：engineFlow を collect して enabled に反映するとより良い
    }

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("Maintenance / Backlog", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    MidnightExportScheduler.scheduleNext(ctx)
                    uiMsg = "scheduled next 0:00"
                }
            }) { Text("Run backlog (schedule)") }

            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    MidnightExportScheduler.runNow(ctx) // ★今すぐ実行
                    uiMsg = "enqueued now"
                }
            }) { Text("Run now") }
        }

        Spacer(Modifier.height(8.dp))
        Text("oldest not uploaded: ${oldest ?: "-"}")
        if (uiMsg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(uiMsg, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(10.dp))
        // ★任意：アップロード有効化トグル
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable Drive upload")
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    scope.launch {
                        val repo = com.mapconductor.plugin.provider.geolocation.DrivePrefsRepository(ctx)
                        val engine = if (on)
                            com.mapconductor.plugin.provider.geolocation.config.UploadEngine.KOTLIN
                        else
                            com.mapconductor.plugin.provider.geolocation.config.UploadEngine.NONE
                        repo.setEngine(engine)
                    }
                }
            )
        }
    }
}

private suspend fun refreshOldest(
    ctx: android.content.Context,
    onLoaded: (Long?) -> Unit
) = withContext(Dispatchers.IO) {
    val dao = AppDatabase.get(ctx).exportedDayDao()
    onLoaded(dao.oldestNotUploaded()?.epochDay)
}
