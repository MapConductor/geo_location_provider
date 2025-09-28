package com.mapconductor.plugin.provider.geolocation.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.fillMaxWidth
import com.mapconductor.plugin.provider.geolocation.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId.extractFromUrlOrId
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
// ★ 追加
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mapconductor.plugin.provider.geolocation.ui.settings.DriveSettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        AppRoot(
                            onStartTracking = { requestPermissionsAndStartService() },
                            onStopTracking  = { stopLocationService() },
                            onOpenDriveSettings = { navController.navigate("drive_settings") }
                        ) {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                val vm: GeoLocationProviderViewModel = viewModel()
                                GeoLocationProviderScreen(
                                    state = vm.uiState.value,
                                    onButtonClick = { vm.onGeoLocationProviderClicked() }
                                )
                            }
                        }
                    }
                    composable("drive_settings") {
                        DriveSettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val locOk =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

        if (locOk && notifOk) {
            startLocationService()
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_STOP)
        stopService(intent)
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val locOk = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notifOk = if (Build.VERSION.SDK_INT >= 33)
            (granted[Manifest.permission.POST_NOTIFICATIONS] == true) else true
        if (locOk && notifOk) startLocationService()
    }

    private fun requestPermissionsAndStartService() {
        val need = mutableListOf<String>()
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isEmpty()) startLocationService() else permLauncher.launch(need.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onOpenDriveSettings: () -> Unit, // ★ 追加
    content: @Composable () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val repo = remember { GoogleAuthRepository(ctx.applicationContext) }
    val api  = remember { DriveApiClient(context = ctx) }

    val showMsg: (String) -> Unit = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        scope.launch {
            val acct = repo.handleSignInResult(res.data)
            showMsg(acct?.email?.let { "Signed in: $it" } ?: "Sign-in failed")
        }
    }

    var driveMenu by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GeoLocation") },
                actions = {
                    TextButton(onClick = { driveMenu = true }) { Text("Drive") }
                    DropdownMenu(expanded = driveMenu, onDismissRequest = { driveMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Sign in") },
                            onClick = {
                                driveMenu = false
                                launcher.launch(repo.buildSignInIntent())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Get Token") },
                            onClick = {
                                driveMenu = false
                                scope.launch {
                                    val t = repo.getAccessTokenOrNull()
                                    showMsg(t?.let { "Token OK: ${it.take(12)}…" } ?: "Token failed")
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Set Folder ID…") },
                            onClick = {
                                driveMenu = false
                                scope.launch {
                                    val prefs = DrivePrefsRepository(ctx.applicationContext)
                                    folderInput = prefs.folderIdFlow.first().orEmpty()
                                    showFolderDialog = true
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("About.get") },
                            onClick = {
                                driveMenu = false
                                scope.launch {
                                    val token = repo.getAccessTokenOrNull()
                                    val msg = if (token == null) {
                                        "No token"
                                    } else {
                                        val r = withContext(Dispatchers.IO) {
                                            api.aboutGet(token)
                                        }
                                        when (r) {
                                            is ApiResult.Success ->
                                                "About 200: ${r.data.user?.emailAddress ?: "-"}"
                                            is ApiResult.HttpError ->
                                                "About ${r.code}: ${r.body.take(160)}"
                                            is ApiResult.NetworkError ->
                                                "About network: ${r.exception.message}"
                                            else -> "About: unexpected result"
                                        }
                                    }
                                    showMsg(msg)
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Validate Folder") },
                            onClick = {
                                driveMenu = false
                                scope.launch {
                                    val token = repo.getAccessTokenOrNull()
                                    val prefs = DrivePrefsRepository(ctx.applicationContext)
                                    val id = prefs.folderIdFlow.first().orEmpty()
                                    val msg = when {
                                        token == null -> "No token"
                                        id.isBlank()  -> "Folder ID empty"
                                        else -> {
                                            val r = withContext(Dispatchers.IO) {
                                                api.validateFolder(token, id)
                                            }
                                            when (r) {
                                                is ApiResult.Success ->
                                                    if (r.data.isFolder)
                                                        "Folder OK: ${r.data.name} (${r.data.id})"
                                                    else
                                                        "Not a folder: ${r.data.mimeType}"
                                                is ApiResult.HttpError ->
                                                    "Folder ${r.code}: ${r.body.take(160)}"
                                                is ApiResult.NetworkError ->
                                                    "Folder network: ${r.exception.message}"
                                                else ->
                                                    "Folder: unexpected result"
                                            }
                                        }
                                    }
                                    showMsg(msg)
                                }
                            }
                        )
                        androidx.compose.material3.Divider()
                        DropdownMenuItem(
                            text = { Text("Drive 設定（詳細）") },
                            onClick = {
                                driveMenu = false
                                onOpenDriveSettings()
                            }
                        )
                    }
                    TextButton(onClick = onStopTracking) { Text("Stop") }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onStartTracking,
                text = { Text("開始") },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner)) {
            content()
        }
    }

    if (showFolderDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Set Google Drive Folder ID") },
            text = {
                OutlinedTextField(
                    value = folderInput,
                    onValueChange = { folderInput = it },
                    label = { Text("Folder ID or URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val prefs = DrivePrefsRepository(ctx.applicationContext)
                        val extracted = extractFromUrlOrId(folderInput)
                        if (extracted.isNullOrBlank()) {
                            showMsg("Invalid Folder ID or URL")
                            return@launch
                        }
                        prefs.setFolderId(requireNotNull(extracted))
                        showFolderDialog = false
                        showMsg("Saved Folder ID: $extracted")
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}
