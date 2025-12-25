package com.mapconductor.plugin.provider.geolocation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.auth.AppAuthAuth
import com.mapconductor.plugin.provider.geolocation.auth.AppAuthSignInActivity
import com.mapconductor.plugin.provider.geolocation.auth.CredentialManagerAuth
import com.mapconductor.plugin.provider.geolocation.config.UploadOutputFormat
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import com.mapconductor.plugin.provider.geolocation.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.export.GpxExporter
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.prefs.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.prefs.UploadPrefsRepository
import com.mapconductor.plugin.provider.storageservice.StorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveSettingsScreen(
    vm: DriveSettingsViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val status by vm.status.collectAsState()
    val folderId by vm.folderId.collectAsState()
    val account by vm.accountEmail.collectAsState()
    val lastRefresh by vm.tokenLastRefresh.collectAsState()
    val authMethod by vm.authMethod.collectAsState()
    val cmLoggedIn by vm.cmLoggedIn.collectAsState()
    val appAuthLoggedIn by vm.appAuthLoggedIn.collectAsState()

    val lastRefreshText = remember(lastRefresh) {
        if (lastRefresh > 0L) {
            val dt = Instant.ofEpochMilli(lastRefresh)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            "Token refreshed: $dt"
        } else {
            null
        }
    }

    var showPreviewDialog by remember { mutableStateOf(false) }
    var uiMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Auth method selection
        Text("Auth method", style = MaterialTheme.typography.titleMedium)
        Column {
            DriveAuthMethod.values().forEach { method ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = authMethod == method,
                        onClick = { vm.setAuthMethod(method) }
                    )
                    Text(method.label)
                }
            }
        }

        HorizontalDivider(color = DividerDefaults.color)

        // Basic Drive settings
        Text("Drive settings", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = folderId,
            onValueChange = { vm.updateFolderId(it) },
            label = { Text("Folder URL or ID") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.validateFolder() }) {
                Text("Validate folder")
            }
        }

        HorizontalDivider(color = DividerDefaults.color)

        // Auth-method specific UI
        Text("Auth actions", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Row 1: Credential Manager actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = authMethod == DriveAuthMethod.CREDENTIAL_MANAGER,
                    onClick = {
                        val provider = CredentialManagerAuth.get(ctx)
                        if (!cmLoggedIn) {
                            scope.launch {
                                val credential = provider.signIn(ctx)
                                if (credential != null) {
                                    vm.setCmLoggedIn(true)
                                    vm.setStatus("CM sign-in OK.")
                                    // Persist a "signed in" marker so UploadSettings
                                    // can treat Drive as configured.
                                    vm.markCredentialManagerSignedIn()
                                } else {
                                    vm.setStatus("CM sign-in canceled or failed.")
                                }
                            }
                        } else {
                            scope.launch(Dispatchers.IO) {
                                provider.signOut()
                                withContext(Dispatchers.Main) {
                                    vm.setCmLoggedIn(false)
                                    vm.clearAuthInfo()
                                    vm.setStatus("CM signed out.")
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        if (!cmLoggedIn) "CM: Start sign-in" else "CM: Sign out"
                    )
                }

                OutlinedButton(
                    enabled = authMethod == DriveAuthMethod.CREDENTIAL_MANAGER && cmLoggedIn,
                    onClick = {
                        // Use DriveSettingsViewModel.callAboutGet() to:
                        // - Probe token via the selected Credential Manager account.
                        // - Call Drive API aboutGet to resolve account email.
                        // - Persist accountEmail / tokenUpdatedAt in DrivePrefs.
                        // This ensures UploadSettings (which checks DrivePrefs.accountEmail)
                        // can see that Drive auth is configured.
                        vm.callAboutGet()
                    }
                ) {
                    Text("CM: Get token & account")
                }
            }

            // Row 2: AppAuth actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = authMethod == DriveAuthMethod.APPAUTH,
                    onClick = {
                        if (!appAuthLoggedIn) {
                            vm.setAppAuthLoggedIn(true)
                            val intent = android.content.Intent(
                                ctx,
                                AppAuthSignInActivity::class.java
                            )
                            ctx.startActivity(intent)
                        } else {
                            val provider = AppAuthAuth.get(ctx)
                            scope.launch(Dispatchers.IO) {
                                provider.signOut()
                                withContext(Dispatchers.Main) {
                                    vm.setAppAuthLoggedIn(false)
                                    vm.clearAuthInfo()
                                    vm.setStatus("AppAuth signed out.")
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        if (!appAuthLoggedIn) "AppAuth: Start sign-in" else "AppAuth: Sign out"
                    )
                }

                  OutlinedButton(
                      enabled = authMethod == DriveAuthMethod.APPAUTH && appAuthLoggedIn,
                      onClick = {
                          val provider = AppAuthAuth.get(ctx)
                          scope.launch(Dispatchers.IO) {
                              val token = provider.getAccessToken()
                              withContext(Dispatchers.Main) {
                                  if (token != null) {
                                      vm.setAppAuthLoggedIn(true)
                                      vm.markAppAuthSignedIn()
                                      vm.setStatus("AppAuth Token OK: ${token.take(12)}...")
                                  } else {
                                      vm.setStatus("AppAuth token is null (sign-in required?)")
                                  }
                              }
                          }
                      }
                ) {
                    Text("AppAuth: Get token")
                }
            }
        }

        HorizontalDivider(color = DividerDefaults.color)

        // Backup and preview actions
        Text("Backup & preview", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = {
                vm.runBacklogNow()
            }) {
                Text("Backup days before today")
            }

            OutlinedButton(onClick = { showPreviewDialog = true }) {
                Text("Today preview")
            }
        }

        HorizontalDivider(color = DividerDefaults.color)

        // Status section
        if (!status.isNullOrBlank()) {
            Text("Status:", style = MaterialTheme.typography.titleMedium)
            Text(status)
        }
        if (!account.isNullOrBlank()) {
            Text("Account: $account")
        }
        if (lastRefreshText != null) {
            Text(lastRefreshText)
        }
        if (!uiMsg.isNullOrBlank()) {
            Text(uiMsg!!)
        }
    }

    if (showPreviewDialog) {
        TodayPreviewDialog(
            onDismiss = { showPreviewDialog = false },
            onUpload = {
                showPreviewDialog = false
                // Run upload path on IO dispatcher
                scope.launch(Dispatchers.IO) {
                    val msg = runTodayPreviewIO(ctx, upload = true)
                    withContext(Dispatchers.Main) { uiMsg = msg }
                }
            },
            onLocalOnly = {
                showPreviewDialog = false
                // Run local-only path on IO dispatcher
                scope.launch(Dispatchers.IO) {
                    val msg = runTodayPreviewIO(ctx, upload = false)
                    withContext(Dispatchers.Main) { uiMsg = msg }
                }
            }
        )
    }
}

/**
 * Dialog for "today preview" that asks whether to upload or keep local.
 */
@Composable
private fun TodayPreviewDialog(
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
    onLocalOnly: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Today preview") },
          text = {
              Text(
                  "Upload today data?\n" +
                      "- If you upload, the local ZIP file in Downloads will be deleted.\n" +
                      "- If you do not upload, the ZIP file will remain in Downloads.\n" +
                      "- Room data will NOT be deleted in either case."
              )
          },
        confirmButton = {
            Button(onClick = onUpload) {
                Text("Upload")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onLocalOnly) {
                Text("Do not upload")
            }
        }
    )
}

/**
 * Run "today preview" for [upload] == true/false.
 * Targets only records from today (00:00 to now).
 */
private suspend fun runTodayPreviewIO(
    ctx: android.content.Context,
    upload: Boolean
): String = withContext(Dispatchers.IO) {
    val uploadPrefs = UploadPrefsRepository(ctx)
    val zoneId = runCatching { uploadPrefs.zoneIdFlow.first() }
        .getOrNull().orEmpty()
    val outputFormat = runCatching { uploadPrefs.outputFormatFlow.first() }
        .getOrNull() ?: UploadOutputFormat.GEOJSON
    val zone = try {
        if (zoneId.isNotBlank()) ZoneId.of(zoneId) else ZoneId.of("Asia/Tokyo")
    } catch (_: Throwable) {
        ZoneId.of("Asia/Tokyo")
    }
    val nowJst = ZonedDateTime.now(zone)
    val today0 = nowJst.truncatedTo(ChronoUnit.DAYS)
    val todayEpochDay = today0.toLocalDate().toEpochDay()

    // Load LocationSample list via StorageService
    val all = StorageService.getAllLocations(ctx)

    // Safely extract timestamp in millis from entity (tolerant to field name differences)
    fun extractMillis(rec: Any): Long {
        val candidates = arrayOf(
            "timestampMillis", "timeMillis", "createdAtMillis",
            "timestamp", "createdAt", "recordedAt", "epochMillis"
        )
        for (name in candidates) {
            runCatching {
                val f = rec.javaClass.getDeclaredField(name)
                f.isAccessible = true
                val v = f.get(rec)
                when (v) {
                    is Long -> return v
                    is Number -> return v.toLong()
                }
            }
        }
        return 0L
    }

    val todays = all.filter { rec ->
        val millis = extractMillis(rec)
        if (millis <= 0L) {
            false
        } else {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
                .toLocalDate()
                .toEpochDay() == todayEpochDay
        }
    }

    if (todays.isEmpty()) {
        return@withContext "No data for today."
    }

    val baseName = "glp-" + DateTimeFormatter.ofPattern("yyyyMMdd")
        .format(LocalDate.ofEpochDay(todayEpochDay)) + "_prev"
    val formatLabel = when (outputFormat) {
        UploadOutputFormat.GEOJSON -> "GeoJSON"
        UploadOutputFormat.GPX     -> "GPX"
    }

    // Export to Downloads/GeoLocationProvider as ZIP (GeoJSON or GPX inside).
    val outUri = when (outputFormat) {
        UploadOutputFormat.GEOJSON -> GeoJsonExporter.exportToDownloads(
            context = ctx,
            records = todays,
            baseName = baseName,
            compressAsZip = true
        )
        UploadOutputFormat.GPX -> GpxExporter.exportToDownloads(
            context = ctx,
            records = todays,
            baseName = baseName,
            compressAsZip = true
        )
    } ?: return@withContext "Failed to create ZIP."

    if (!upload) {
        // Save only: keep Room data
        return@withContext "Saved today's data to Downloads as $baseName.zip ($formatLabel, Room data kept)."
    }

    // Upload flow
    val tokenProvider = CredentialManagerAuth.get(ctx)
    val token = tokenProvider.getAccessToken()
    if (token == null) {
        runCatching { ctx.contentResolver.delete(outUri, null, null) }
        return@withContext "Drive authorization is missing. ZIP was deleted; Room data is kept."
    }

    // Resolve folder id from DrivePrefs (UI) first, then fall back to AppPrefs.
    val drivePrefs = DrivePrefsRepository(ctx)
    val uiFolderRaw = runCatching { drivePrefs.folderIdFlow.first() }.getOrNull().orEmpty()
    val appSnapshot = AppPrefs.snapshot(ctx)
    val rawFolder = uiFolderRaw.ifBlank { appSnapshot.folderId }

    // Pre-resolve folder id via Drive API (handles shortcuts / resourceKey).
    val folderResourceKey = runCatching { drivePrefs.folderResourceKeyFlow.first() }
        .getOrNull()
    val driveApi = DriveApiClient(ctx)
    val resolvedFolderId = driveApi.resolveFolderIdForUploadOrNull(
        token = token,
        idOrUrl = rawFolder,
        resourceKey = folderResourceKey
    )
    val folderId = resolvedFolderId ?: DriveFolderId.extractFromUrlOrId(rawFolder)
    val engine = appSnapshot.engine
    val uploader = UploaderFactory.create(
        ctx,
        engine,
        tokenProvider = tokenProvider
    )

    if (uploader == null || folderId.isNullOrBlank()) {
        // Delete ZIP, keep Room data
        runCatching { ctx.contentResolver.delete(outUri, null, null) }
        return@withContext "Upload settings are incomplete (engine or folderId). ZIP was deleted; Room data is kept."
    }

    var success = false
    for (attempt in 0 until 5) {
        when (val result = uploader.upload(outUri, folderId, null)) {
            is UploadResult.Success -> {
                // Preview mode: Room data is not deleted
                success = true
                break
            }

            is UploadResult.Failure -> {
                if (attempt < 4) {
                    // Exponential backoff: 15s, 30s, 60s, 120s
                    delay(15_000L * (1 shl attempt))
                }
            }
        }
    }

    // Delete ZIP regardless of success/failure; keep Room data
    runCatching { ctx.contentResolver.delete(outUri, null, null) }

    if (success) {
        "Uploaded today's data as $baseName.zip ($formatLabel) to folder $folderId. Local ZIP was deleted; Room data is kept."
    } else {
        "Failed to upload today's data ($formatLabel) after 5 attempts (folder=$folderId). ZIP was deleted; Room data is kept."
    }
}
