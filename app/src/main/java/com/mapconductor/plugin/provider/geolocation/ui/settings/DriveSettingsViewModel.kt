package com.mapconductor.plugin.provider.geolocation.ui.settings

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine

class DriveSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = DrivePrefsRepository(app)
    private val auth = GoogleAuthRepository(app)
    // ★ TODO() を解消：Application コンテキストで初期化
    private val api = DriveApiClient(context = app.applicationContext)

    val folderId: StateFlow<String> =
        prefs.folderIdFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val accountEmail: StateFlow<String> =
        prefs.accountEmailFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val tokenLastRefresh: StateFlow<Long> =
        prefs.tokenLastRefreshFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    fun buildSignInIntent(): Intent = auth.buildSignInIntent()

    fun onSignInResult(data: Intent?) {
        viewModelScope.launch(Dispatchers.IO) {
            val acct = auth.handleSignInResult(data)
            if (acct?.email != null) prefs.setAccountEmail(acct.email!!)
            _status.value = acct?.email?.let { "Signed in: $it" } ?: "Sign-in failed"
        }
    }

    fun updateFolderId(id: String) {
        viewModelScope.launch { prefs.setFolderId(id) }
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
                is ApiResult.Success -> _status.value =
                    "About 200: ${r.data.user?.emailAddress ?: "unknown"}"
                is ApiResult.HttpError -> _status.value = "About ${r.code}: ${r.body}"
                is ApiResult.NetworkError -> _status.value = "About network: ${r.exception.message}"
            }
        }
    }

    fun validateFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val token = auth.getAccessTokenOrNull()
            if (token == null) { _status.value = "No token"; return@launch }

            val raw = folderId.value
            val id = DriveFolderId.extractFromUrlOrId(raw)
            val rk = DriveFolderId.extractResourceKey(raw)
            if (id.isNullOrBlank()) { _status.value = "Invalid folder URL/ID"; return@launch }

            // 1回目: 生ID + resourceKey 付きで検証
            when (val r = api.validateFolder(token, id, rk)) {
                is ApiResult.Success -> {
                    var resolvedId = r.data.id
                    var detail = r.data

                    // ショートカットなら、ターゲットへ追従して再検証
                    if (detail.mimeType == "application/vnd.google-apps.shortcut" && !detail.shortcutTargetId.isNullOrBlank()) {
                        when (val r2 = api.validateFolder(token, detail.shortcutTargetId!!, null)) {
                            is ApiResult.Success -> {
                                resolvedId = r2.data.id
                                detail = r2.data
                            }
                            is ApiResult.HttpError -> {
                                _status.value = "Shortcut target ${r2.code}: ${r2.body}"
                                return@launch
                            }
                            is ApiResult.NetworkError -> {
                                _status.value = "Shortcut target network: ${r2.exception.message}"
                                return@launch
                            }
                        }
                    }

                    if (!detail.isFolder) {
                        _status.value = "Not a folder: ${detail.mimeType}"
                        return@launch
                    }
                    if (!detail.canAddChildren) {
                        _status.value = "No write permission for this folder"
                        return@launch
                    }

                    // 正規化IDを保存（ショートカット→実体へ）
                    prefs.setFolderId(resolvedId)
                    _status.value = "Folder OK: ${detail.name} ($resolvedId)"

                    // ここでエンジンをONにするなら：
                    // prefs.setEngine(UploadEngine.KOTLIN)
                }
                is ApiResult.HttpError    -> _status.value = "Folder ${r.code}: ${r.body}"
                is ApiResult.NetworkError -> _status.value = "Folder network: ${r.exception.message}"
            }
        }
    }

    fun runBacklogNow() {
        viewModelScope.launch(Dispatchers.Default) {
            MidnightExportScheduler.runNow(getApplication())
        }
    }
}
