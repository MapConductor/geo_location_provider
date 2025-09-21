package com.mapconductor.plugin.provider.geolocation

import androidx.compose.material3.HorizontalDivider
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

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
    }
}