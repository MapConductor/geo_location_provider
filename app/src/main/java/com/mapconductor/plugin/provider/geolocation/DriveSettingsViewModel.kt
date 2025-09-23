package com.mapconductor.plugin.provider.geolocation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
            val id = folderId.value
            if (token == null) { _status.value = "No token"; return@launch }
            if (id.isBlank()) { _status.value = "Folder ID empty"; return@launch }
            when (val r = api.validateFolder(token, id)) {
                is ApiResult.Success -> {
                    val f = r.data
                    _status.value = if (f.isFolder)
                        "Folder OK: ${f.name} (${f.id})"
                    else
                        "Not a folder: ${f.mimeType}"
                }
                is ApiResult.HttpError -> _status.value = "Folder ${r.code}: ${r.body}"
                is ApiResult.NetworkError -> _status.value = "Folder network: ${r.exception.message}"
            }
        }
    }
}
