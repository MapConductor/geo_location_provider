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
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService

// Drive フォルダURL or ID を受け取り、IDを取り出す（/folders/<ID> or ?id=<ID> を想定）
private fun extractDriveFolderId(input: String): String {
    val t = input.trim()
    val re1 = Regex("""/folders/([a-zA-Z0-9_-]+)""")
    val re2 = Regex("""[?&]id=([a-zA-Z0-9_-]+)""")
    return re1.find(t)?.groupValues?.get(1)
        ?: re2.find(t)?.groupValues?.get(1)
        ?: t // どれでもなければそのままIDとして扱う
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(
                    onStartTracking = { requestPermissionsAndStartService() },
                    onStopTracking  = { stopLocationService() }
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
        }
    }

    override fun onStart() {
        super.onStart()
        // 既に権限が揃っているなら”自動開始”
        val locOk =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

        if (locOk && notifOk) {
            startLocationService()           // ← これで起動直後に自動開始
        }
        // ※ 初回から自動で許可ダイアログも出したいなら、下の行に置き換え可
        // else requestPermissionsAndStartService()
    }

    private fun startLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_START) // ACTION_START未定義ならこの行を削除
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_STOP) // 未定義なら setAction を削除
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
    content: @Composable () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val repo = remember { GoogleAuthRepository(ctx.applicationContext) }
    // ★ TODO() を削除してデフォルトOkHttpを使う
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

    // フォルダID入力ダイアログ用の状態
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
                                    folderInput = prefs.folderIdFlow.first() // 既存値を初期表示
                                    showFolderDialog = true
                                }
                            }
                        )
                        // --- About.get ---
                        DropdownMenuItem(
                            text = { Text("About.get") },
                            onClick = {
                                driveMenu = false
                                scope.launch {
                                    val token = repo.getAccessTokenOrNull()
                                    val msg = if (token == null) {
                                        "No token"
                                    } else {
                                        when (val r = api.aboutGet(token)) {
                                            is ApiResult.Success ->
                                                "About 200: ${r.data.user?.emailAddress ?: "-"}"
                                            is ApiResult.HttpError ->
                                                "About ${r.code}: ${r.body.take(160)}"
                                            is ApiResult.NetworkError ->
                                                "About network: ${r.exception.message}"
                                            else ->
                                                "About: unexpected result"
                                        }
                                    }
                                    showMsg(msg)
                                }
                            }
                        )
                        // --- Validate Folder ---
                        DropdownMenuItem(
                            text = { Text("Validate Folder") },
                            onClick = {
                                driveMenu = false
                                scope.launch {
                                    val token = repo.getAccessTokenOrNull()
                                    val prefs = DrivePrefsRepository(ctx.applicationContext)
                                    val id = prefs.folderIdFlow.first()
                                    val msg = when {
                                        token == null -> "No token"
                                        id.isBlank()  -> "Folder ID empty"
                                        else -> {
                                            when (val r = api.validateFolder(token, id)) {
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

    // フォルダID入力ダイアログ
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
                        val id = extractDriveFolderId(folderInput)
                        prefs.setFolderId(id)
                        showFolderDialog = false
                        showMsg("Saved Folder ID: $id")
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}
