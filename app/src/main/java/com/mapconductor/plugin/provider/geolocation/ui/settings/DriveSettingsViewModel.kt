package com.mapconductor.plugin.provider.geolocation.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DriveSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = DrivePrefsRepository(app)
    private val auth = GoogleAuthRepository(app)
    private val api = DriveApiClient(context = app.applicationContext)

    val folderId: StateFlow<String> =
        prefs.folderIdFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val accountEmail: StateFlow<String> =
        prefs.accountEmailFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val tokenLastRefresh: StateFlow<Long> =
        prefs.tokenLastRefreshFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun buildSignInIntent() = auth.buildSignInIntent()

    fun onSignInResult(data: android.content.Intent?) {
        viewModelScope.launch(Dispatchers.IO) {
            val acct = auth.handleSignInResult(data)
            if (acct?.email != null) prefs.setAccountEmail(acct.email!!)
            _status.value = acct?.email?.let { "Signed in: $it" } ?: "Sign-in failed"
        }
    }

    fun updateFolderId(idOrUrl: String) {
        // そのまま表示用に保存（URLでもOK）
        viewModelScope.launch { prefs.setFolderId(idOrUrl) }
    }

    fun getToken() {
        viewModelScope.launch(Dispatchers.IO) {
            val token = auth.getAccessTokenOrNull()
            _status.value = if (token != null) {
                prefs.markTokenRefreshed(System.currentTimeMillis())
                "Token OK: ${token.take(12)}…"
            } else {
                "Token failed"
            }
        }
    }

    fun callAboutGet() {
        viewModelScope.launch(Dispatchers.IO) {
            val token = auth.getAccessTokenOrNull()
            if (token == null) { _status.value = "No token"; return@launch }
            when (val r = api.aboutGet(token)) {
                is ApiResult.Success     -> _status.value = "About 200: ${r.data.user?.emailAddress ?: "unknown"}"
                is ApiResult.HttpError   -> _status.value = "About ${r.code}: ${r.body}"
                is ApiResult.NetworkError-> _status.value = "About network: ${r.exception.message}"
            }
        }
    }

    /** Validate Folder: URL/ID から id と resourceKey を抽出して検証＆保存 */
    fun validateFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val token = auth.getAccessTokenOrNull()
            if (token == null) { _status.value = "No token"; return@launch }

            val raw = folderId.value
            val id  = DriveFolderId.extractFromUrlOrId(raw)
            val rk  = DriveFolderId.extractResourceKey(raw)
            if (id.isNullOrBlank()) { _status.value = "Invalid folder URL/ID"; return@launch }

            // resourceKey を DataStore に保存（アップロード時に利用）
            prefs.setFolderResourceKey(rk)

            // 生ID + resourceKey 付きで検証
            when (val r = api.validateFolder(token, id, rk)) {
                is ApiResult.Success -> {
                    var resolvedId = r.data.id
                    var detail = r.data

                    // ショートカットなら、ターゲットへ追従して再検証（resourceKeyは不要）
                    if (!detail.shortcutTargetId.isNullOrBlank()) {
                        when (val r2 = api.validateFolder(token, detail.shortcutTargetId!!, null)) {
                            is ApiResult.Success   -> { resolvedId = r2.data.id; detail = r2.data }
                            is ApiResult.HttpError -> { _status.value = "Shortcut target ${r2.code}: ${r2.body}"; return@launch }
                            is ApiResult.NetworkError -> { _status.value = "Shortcut target network: ${r2.exception.message}"; return@launch }
                        }
                    }

                    if (!detail.isFolder) { _status.value = "Not a folder: ${detail.mimeType}"; return@launch }
                    if (!detail.canAddChildren) { _status.value = "No write permission for this folder"; return@launch }

                    // 実体フォルダIDを保存
                    prefs.setFolderId(resolvedId)
                    _status.value = "Folder OK: ${detail.name} ($resolvedId)"
                }
                is ApiResult.HttpError    -> _status.value = "Folder ${r.code}: ${r.body}"
                is ApiResult.NetworkError -> _status.value = "Folder network: ${r.exception.message}"
            }
        }
    }

    /** ★ P8テスト：サンプルテキストを作って Drive にアップロード */
    fun uploadSampleNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val token = auth.getAccessTokenOrNull()
            if (token == null) { _status.value = "Sign-in required"; return@launch }

            val raw = folderId.value
            val parentId = DriveFolderId.extractFromUrlOrId(raw)
            if (parentId.isNullOrBlank()) { _status.value = "Folder URL/ID is empty"; return@launch }

            val uploader = UploaderFactory.create(getApplication(), com.mapconductor.plugin.provider.geolocation.config.UploadEngine.KOTLIN)
                ?: run { _status.value = "Uploader not available"; return@launch }

            val now = System.currentTimeMillis()
            val name = "drive-sample-$now.txt"
            val text = "Hello from GeoLocationProvider @ $now\n"
            val uri = createSampleTextInDownloads(getApplication(), name, text)
                ?: run { _status.value = "Failed to create sample file"; return@launch }

            when (val r = uploader.upload(uri, parentId, name)) {
                is com.mapconductor.plugin.provider.geolocation.drive.UploadResult.Success ->
                    _status.value = "Upload OK: ${r.name}\n${r.webViewLink}"
                is com.mapconductor.plugin.provider.geolocation.drive.UploadResult.Failure ->
                    _status.value = "Upload NG: ${r.code} ${r.message ?: ""}\n${r.body.take(300)}"
            }
        }
    }

    private fun createSampleTextInDownloads(
        ctx: Context,
        displayName: String,
        content: String
    ): android.net.Uri? {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            if (android.os.Build.VERSION.SDK_INT >= 29)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
            os.flush()
        } ?: return null

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    /** 最終仕様：前日以前を即時バックアップ（Run now 撤去に伴い Worker を直接起動） */
    fun runBacklogNow() {
        MidnightExportWorker.runBacklogNow(getApplication())
    }
}
