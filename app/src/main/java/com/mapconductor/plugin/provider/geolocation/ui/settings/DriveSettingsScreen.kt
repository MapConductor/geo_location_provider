package com.mapconductor.plugin.provider.geolocation.ui.settings

import androidx.compose.material3.HorizontalDivider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.core.data.room.AppDatabase
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DriveSettingsScreen(vm: DriveSettingsViewModel = viewModel()) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsState()
    val folderId by vm.folderId.collectAsState()
    val account by vm.accountEmail.collectAsState()
    val lastRefresh by vm.tokenLastRefresh.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        vm.onSignInResult(res.data)
    }

    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Google Drive Settings", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = folderId,
            onValueChange = { vm.updateFolderId(it) },
            label = { Text("Folder ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(text = "Account: ${'$'}account")
        if (lastRefresh > 0) Text(text = "Token refreshed: ${'$'}lastRefresh")

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

        BacklogPanel()
    }
}

@Composable
fun BacklogPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var oldest by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String>("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).exportedDayDao()
            oldest = dao.oldestNotUploaded()?.epochDay
        }
    }

    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("* Backlog", style = MaterialTheme.typography.titleMedium)
        Text("oldest not uploaded: ${oldest ?: -1L}")
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                MidnightExportScheduler.scheduleNext(ctx) // 直近0:00予約。テスト用に即時ワーカー起動でもOK
                status = "scheduled"
            }
        }) { Text("Run backlog") }
        if (status.isNotEmpty()) Text(status)
    }
}
