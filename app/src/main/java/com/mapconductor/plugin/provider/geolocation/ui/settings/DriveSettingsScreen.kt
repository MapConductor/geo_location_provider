package com.mapconductor.plugin.provider.geolocation.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.mapconductor.plugin.provider.geolocation.auth.CredentialManagerAuth
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import com.mapconductor.plugin.provider.geolocation.export.GeoJsonExporter
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportWorker
import com.mapconductor.plugin.provider.storageservice.StorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val credentialManagerTokenProvider = remember { CredentialManagerAuth.get(ctx) }

    val status by vm.status.collectAsState()
    val folderId by vm.folderId.collectAsState()
    val account by vm.accountEmail.collectAsState()
    val lastRefresh by vm.tokenLastRefresh.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res -> vm.onSignInResult(res.data) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Drive Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Drive 基本設定 ---
            OutlinedTextField(
                value = folderId,
                onValueChange = { vm.updateFolderId(it) },
                label = { Text("Folder ID / URL") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(text = "Account: $account")
            if (lastRefreshText != null) {
                Text(text = lastRefreshText, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { launcher.launch(vm.buildSignInIntent()) }) {
                    Text("Sign in")
                }
                Button(
                    onClick = {
                        scope.launch {
                            val credential = credentialManagerTokenProvider.signIn(ctx)
                            if (credential != null) {
                                vm.setStatus("Credential Manager sign-in completed")
                            } else {
                                vm.setStatus("Credential Manager sign-in failed")
                            }
                        }
                    }
                ) {
                    Text("Sign in (CM)")
                }
                Button(
                    onClick = {
                        scope.launch {
                            val token = credentialManagerTokenProvider.getAccessToken()
                            if (token != null) {
                                vm.setStatus("CM Token OK: ${token.take(12)}…")
                            } else {
                                vm.setStatus("CM Token failed")
                            }
                        }
                    }
                ) {
                    Text("Get Token (CM)")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.callAboutGet() }) {
                    Text("About.get")
                }
                OutlinedButton(onClick = { vm.validateFolder() }) {
                    Text("Validate Folder")
                }
                Button(onClick = { vm.uploadSampleNow() }) {
                    Text("Sample Upload")
                }
            }

            HorizontalDivider(
                modifier = Modifier,
                thickness = DividerDefaults.Thickness,
                color = DividerDefaults.color
            )

            Text(text = status, style = MaterialTheme.typography.bodyMedium)

            // --- バックアップ操作（前日以前のバックログ + 今日のプレビュー） ---
            BackupSection(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * 「前日以前をBackup」「今日のPreview」を提供するセクション。
 *
 * - 前日以前をBackup: MidnightExportWorker に任せる（前日より前を日付ごとにアップロード）
 * - 今日のPreview: 0:00〜現在のデータを ZIP 化し、アップロード有無を選択可能
 */
@Composable
private fun BackupSection(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uiMsg by remember { mutableStateOf("") }
    var showPreviewChoice by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                // 前日以前のバックログを即時実行（UniqueWork REPLACE）
                scope.launch(Dispatchers.Default) {
                    MidnightExportWorker.runNow(ctx)
                    uiMsg = "前日以前のバックアップを開始しました。"
                }
            }) {
                Text("前日以前をBackup")
            }

            Button(onClick = { showPreviewChoice = true }) {
                Text("今日のPreview")
            }
        }

        if (uiMsg.isNotEmpty()) {
            Text(uiMsg, style = MaterialTheme.typography.bodySmall)
        }
    }

    // 「今日のPreview」の選択ダイアログ
    if (showPreviewChoice) {
        AlertDialog(
            onDismissRequest = { showPreviewChoice = false },
            title = { Text("今日のPreview") },
            text = {
                Text(
                    "アップロードしますか？\n" +
                        "アップロードする場合はローカルの GeoJSON/zip を削除します。\n" +
                        "アップロードしない場合は Downloads に保存します。\n" +
                        "Room のデータは削除しません。"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPreviewChoice = false
                    // アップロードする: IO スレッドで実行
                    scope.launch(Dispatchers.IO) {
                        val msg = runTodayPreviewIO(ctx, upload = true)
                        withContext(Dispatchers.Main) { uiMsg = msg }
                    }
                }) {
                    Text("アップロードする")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showPreviewChoice = false
                    // アップロードしない: IO スレッドで実行
                    scope.launch(Dispatchers.IO) {
                        val msg = runTodayPreviewIO(ctx, upload = false)
                        withContext(Dispatchers.Main) { uiMsg = msg }
                    }
                }) {
                    Text("アップロードしない")
                }
            }
        )
    }
}

/**
 * 今日(0:00〜現在)のみを対象に Preview 実行する処理本体。
 * UI から呼び出す想定。
 */
private suspend fun runTodayPreviewIO(
    ctx: android.content.Context,
    upload: Boolean
): String = withContext(Dispatchers.IO) {
    val zone = ZoneId.of("Asia/Tokyo")
    val nowJst = ZonedDateTime.now(zone)
    val today0 = nowJst.truncatedTo(ChronoUnit.DAYS)
    val todayEpochDay = today0.toLocalDate().toEpochDay()

    // LocationSample エンティティを StorageService 経由で取得
    val all = StorageService.getAllLocations(ctx)

    // エンティティのタイムスタンプを安全に取得（フィールド名の違いに対応）
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
        return@withContext "今日のデータはありません。"
    }

    val baseName = "glp-" + DateTimeFormatter.ofPattern("yyyyMMdd")
        .format(LocalDate.ofEpochDay(todayEpochDay))

    // ZIP を Downloads/GeoLocationProvider に出力
    val outUri = GeoJsonExporter.exportToDownloads(
        context = ctx,
        records = todays,
        baseName = baseName,
        compressAsZip = true
    ) ?: return@withContext "ZIP の作成に失敗しました。"

    if (!upload) {
        // 保存のみ: Room は削除しない
        return@withContext "今日のデータを Downloads に保存しました（${baseName}.zip）。Room は削除していません。"
    }

    // ここから「アップロードする」経路
    val tokenProvider = CredentialManagerAuth.get(ctx)
    val token = tokenProvider.getAccessToken()
    if (token == null) {
        runCatching { ctx.contentResolver.delete(outUri, null, null) }
        return@withContext "Drive 認可が不足しています。設定画面から権限を付与してください。ZIP は削除しました。Room は削除していません。"
    }

    val snapshot = AppPrefs.snapshot(ctx)
    val folderId = DriveFolderId.extractFromUrlOrId(snapshot.folderId)
    val uploader = UploaderFactory.create(
        ctx,
        snapshot.engine,
        tokenProvider = tokenProvider
    )

    if (uploader == null || folderId.isNullOrBlank()) {
        // ZIP は削除、Room は残す
        runCatching { ctx.contentResolver.delete(outUri, null, null) }
        return@withContext "アップロード設定が不足しています（engine / folderId）。ZIP は削除しました。Room は削除していません。"
    }

    var success = false
    for (attempt in 0 until 5) {
        when (val result = uploader.upload(outUri, folderId, null)) {
            is UploadResult.Success -> {
                // 成功しても Preview なので Room は削除しない
                success = true
                break
            }

            is UploadResult.Failure -> {
                if (attempt < 4) {
                    delay(15_000L * (1 shl attempt)) // 15s,30s,60s,120s
                }
            }
        }
    }

    // ZIP は成功/失敗に関わらず削除。Room は削除しない。
    runCatching { ctx.contentResolver.delete(outUri, null, null) }

    if (success) {
        "今日のデータをアップロードしました（${baseName}.zip）。ローカル ZIP は削除し、Room は削除していません。"
    } else {
        "今日のアップロードに失敗しました（リトライ 5 回）。ZIP は削除し、Room は削除していません。"
    }
}

