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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mapconductor.plugin.provider.geolocation.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId.extractFromUrlOrId
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import com.mapconductor.plugin.provider.geolocation.ui.components.ServiceToggleAction
import com.mapconductor.plugin.provider.geolocation.ui.settings.DriveSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.navigation.compose.currentBackStackEntryAsState
import com.mapconductor.plugin.provider.geolocation.ui.pickup.PickupScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_PICKUP = "pickup"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "home"
                ) {
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

        if (locOk && notifOk) startLocationService()
    }

    private fun startLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLocationService() {
        val intent = Intent(this, GeoLocationService::class.java)
            .setAction(GeoLocationService.ACTION_STOP)
        startService(intent)
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
    onOpenDriveSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    val navController = rememberNavController()

    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 直接利用するリポジトリ/クライアント
    val authRepo = remember { GoogleAuthRepository(ctx.applicationContext) }
    val driveApi = remember { DriveApiClient(context = ctx) }
    val prefs = remember { DrivePrefsRepository(ctx.applicationContext) }

    val showMsg: (String) -> Unit = { msg ->
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    // Sign-in ランチャ
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        scope.launch(Dispatchers.IO) {
            val acct = authRepo.handleSignInResult(res.data)
            launch(Dispatchers.Main) {
                showMsg(acct?.email?.let { "Signed in: $it" } ?: "Sign-in failed")
            }
        }
    }

    var driveMenu by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var folderInput by remember { mutableStateOf("") }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentRoute == ROUTE_PICKUP) "Pickup" else "GeoLocation") },
                navigationIcon = {
                    if (currentRoute == ROUTE_PICKUP) {
                        TextButton(onClick = { navController.popBackStack() }) { Text("Back") }
                    }
                },
                actions = {
                    // Pickup ボタンは HOME のときだけ
                    if (currentRoute != ROUTE_PICKUP) {
                        TextButton(onClick = {
                            navController.navigate(ROUTE_PICKUP) { launchSingleTop = true }
                        }) { Text("Pickup") }
                    }

                    // Drive メニュー
                    TextButton(onClick = { driveMenu = true }) { Text("Drive") }
                    DropdownMenu(expanded = driveMenu, onDismissRequest = { driveMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Sign in") },
                            onClick = {
                                driveMenu = false
                                launcher.launch(authRepo.buildSignInIntent())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Get Token") },
                            onClick = {
                                driveMenu = false
                                scope.launch(Dispatchers.IO) {
                                    val token = authRepo.getAccessTokenOrNull()
                                    launch(Dispatchers.Main) {
                                        if (token != null) {
                                            prefs.markTokenRefreshed(System.currentTimeMillis())
                                            showMsg("Token OK: ${token.take(12)}…")
                                        } else {
                                            showMsg("Token failed")
                                        }
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("About.get") },
                            onClick = {
                                driveMenu = false
                                scope.launch(Dispatchers.IO) {
                                    val token = authRepo.getAccessTokenOrNull()
                                    val msg = if (token == null) {
                                        "No token"
                                    } else {
                                        when (val r = driveApi.aboutGet(token)) {
                                            is ApiResult.Success      -> "About 200: ${r.data.user?.emailAddress ?: "unknown"}"
                                            is ApiResult.HttpError    -> "About ${r.code}: ${r.body}"
                                            is ApiResult.NetworkError -> "About network: ${r.exception.message}"
                                        }
                                    }
                                    launch(Dispatchers.Main) { showMsg(msg) }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Validate Folder") },
                            onClick = {
                                driveMenu = false
                                scope.launch(Dispatchers.IO) {
                                    val token = authRepo.getAccessTokenOrNull()
                                    val msg = if (token == null) {
                                        "No token"
                                    } else {
                                        val raw = prefs.folderIdFlow.first()
                                        val id  = extractFromUrlOrId(raw)
                                        val rk: String = (DriveFolderId.extractResourceKey(raw) as? String).orEmpty()
                                        if (id.isNullOrBlank()) {
                                            "Invalid folder URL/ID"
                                        } else {
                                            prefs.setFolderResourceKey(rk)
                                            when (val r = driveApi.validateFolder(token, id, rk)) {
                                                is ApiResult.Success -> {
                                                    var resolvedId = r.data.id
                                                    var detail = r.data
                                                    if (!detail.shortcutTargetId.isNullOrBlank()) {
                                                        when (val r2 = driveApi.validateFolder(token, detail.shortcutTargetId!!, null)) {
                                                            is ApiResult.Success      -> { resolvedId = r2.data.id; detail = r2.data }
                                                            is ApiResult.HttpError    -> "Shortcut target ${r2.code}: ${r2.body}"
                                                            is ApiResult.NetworkError -> "Shortcut target network: ${r2.exception.message}"
                                                            else -> null
                                                        }?.let { launch(Dispatchers.Main) { showMsg(it.toString()) }; return@launch }
                                                    }
                                                    if (!detail.isFolder) {
                                                        "Not a folder: ${detail.mimeType}"
                                                    } else if (!detail.canAddChildren) {
                                                        "No write permission for this folder"
                                                    } else {
                                                        prefs.setFolderId(resolvedId!!)
                                                        "Folder OK: ${detail.name} ($resolvedId)"
                                                    }
                                                }
                                                is ApiResult.HttpError    -> "Folder ${r.code}: ${r.body}"
                                                is ApiResult.NetworkError -> "Folder network: ${r.exception.message}"
                                            }
                                        }
                                    }
                                    launch(Dispatchers.Main) { showMsg(msg) }
                                }
                            }
                        )

                        HorizontalDivider(
                            Modifier,
                            DividerDefaults.Thickness,
                            DividerDefaults.color
                        )

                        DropdownMenuItem(
                            text = { Text("Drive 設定（詳細）") },
                            onClick = {
                                driveMenu = false
                                onOpenDriveSettings() // ← 外側の NavController（MainActivity 側）で drive_settings に遷移
                            }
                        )
                    }

                    // 既存の Start/Stop トグル等
                    ServiceToggleAction()
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }, // 必要に応じて有効化
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            // ★ NavHost を Scaffold の中に配置し、HOME では content() を表示
            NavHost(navController = navController, startDestination = ROUTE_HOME) {
                composable(ROUTE_HOME) { content() }
                composable(ROUTE_PICKUP) {
                    PickupScreen()
                }
            }
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
                    scope.launch(Dispatchers.IO) {
                        val extractedId = extractFromUrlOrId(folderInput)
                        val rk: String = (DriveFolderId.extractResourceKey(folderInput) as? String).orEmpty()
                        val msg = if (extractedId.isNullOrBlank()) {
                            "Invalid Folder ID or URL"
                        } else {
                            prefs.setFolderId(extractedId!!)
                            prefs.setFolderResourceKey(rk)
                            "Saved Folder ID: $extractedId" + if (rk.isNotEmpty()) " (rk saved)" else ""
                        }
                        launch(Dispatchers.Main) {
                            showFolderDialog = false
                            showMsg(msg)
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text("Cancel") }
            }
        )
    }
}
