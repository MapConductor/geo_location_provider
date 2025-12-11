package com.mapconductor.plugin.provider.geolocation.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.geolocation.auth.CredentialManagerAuth
import com.mapconductor.plugin.provider.geolocation.auth.AppAuthAuth
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.upload.UploaderFactory
import com.mapconductor.plugin.provider.geolocation.prefs.AppPrefs
import com.mapconductor.plugin.provider.geolocation.prefs.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportWorker
import com.mapconductor.plugin.provider.storageservice.StorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DriveSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = DrivePrefsRepository(app)
    private val api = DriveApiClient(context = app.applicationContext)

    val folderId: StateFlow<String> =
        prefs.folderIdFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val accountEmail: StateFlow<String> =
        prefs.accountEmailFlow.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val tokenLastRefresh: StateFlow<Long> =
        prefs.tokenLastRefreshFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val authMethod: StateFlow<DriveAuthMethod> =
        prefs.authMethodFlow
            .map { DriveAuthMethod.fromStorage(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                DriveAuthMethod.CREDENTIAL_MANAGER
            )

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val _cmLoggedIn = MutableStateFlow(false)
    val cmLoggedIn: StateFlow<Boolean> = _cmLoggedIn

    private val _appAuthLoggedIn = MutableStateFlow(false)
    val appAuthLoggedIn: StateFlow<Boolean> = _appAuthLoggedIn

    init {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.backupStatusFlow.collect { msg ->
                if (msg.isNotBlank()) {
                    _status.value = msg
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val provider = AppAuthAuth.get(app)
            val ok = provider.isAuthenticated()
            _appAuthLoggedIn.value = ok
        }
    }

    fun setStatus(message: String) {
        _status.value = message
    }

    fun clearAuthInfo() {
        viewModelScope.launch {
            prefs.setAccountEmail("")
            prefs.setTokenUpdatedAt(0L)
        }
    }

    fun setCmLoggedIn(value: Boolean) {
        _cmLoggedIn.value = value
    }

    fun setAppAuthLoggedIn(value: Boolean) {
        _appAuthLoggedIn.value = value
    }

    fun setAuthMethod(method: DriveAuthMethod) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = authMethod.value
            if (current == method) return@launch

            when (current) {
                DriveAuthMethod.CREDENTIAL_MANAGER -> {
                    if (cmLoggedIn.value) {
                        runCatching {
                            val provider = CredentialManagerAuth.get(getApplication())
                            provider.signOut()
                        }
                        _cmLoggedIn.value = false
                        clearAuthInfo()
                    }
                }
                DriveAuthMethod.APPAUTH           -> {
                    if (appAuthLoggedIn.value) {
                        runCatching {
                            val provider = AppAuthAuth.get(getApplication())
                            provider.signOut()
                        }
                        _appAuthLoggedIn.value = false
                        clearAuthInfo()
                    }
                }
            }

            prefs.setAuthMethod(method.storageValue)
        }
    }

    fun updateFolderId(idOrUrl: String) {
        viewModelScope.launch { prefs.setFolderId(idOrUrl) }
    }

    fun callAboutGet() {
        viewModelScope.launch(Dispatchers.IO) {
            val tokenProvider = CredentialManagerAuth.get(getApplication())
            val token = tokenProvider.getAccessToken()
            if (token == null) {
                _status.value = "Sign-in required"
                return@launch
            }
            when (val r = api.aboutGet(token)) {
                is ApiResult.Success      -> {
                    val email = r.data.user?.emailAddress.orEmpty()
                    if (email.isNotBlank()) {
                        prefs.setAccountEmail(email)
                    }
                    // Remember when a token was successfully obtained from the UI
                    prefs.markTokenRefreshed()
                    _status.value = "About 200: ${if (email.isNotBlank()) email else "unknown"}"
                }
                is ApiResult.HttpError    -> _status.value = "About ${r.code}: ${r.body}"
                is ApiResult.NetworkError -> _status.value = "About network: ${r.exception.message}"
            }
        }
    }

    /** Validate folder URL/ID and extract id and resourceKey. */
    fun validateFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val tokenProvider = CredentialManagerAuth.get(getApplication())
            val token = tokenProvider.getAccessToken()
            if (token == null) {
                _status.value = "Sign-in required"
                return@launch
            }

            val raw = folderId.value
            val id = DriveFolderId.extractFromUrlOrId(raw)
            val rk = DriveFolderId.extractResourceKey(raw)
            if (id.isNullOrBlank()) {
                _status.value = "Invalid folder URL/ID"
                return@launch
            }

            prefs.setFolderResourceKey(rk)

            when (val r = api.validateFolder(token, id, rk)) {
                is ApiResult.Success -> {
                    var resolvedId = r.data.id
                    var detail = r.data

                    if (!detail.shortcutTargetId.isNullOrBlank()) {
                        when (val r2 = api.validateFolder(token, detail.shortcutTargetId!!, null)) {
                            is ApiResult.Success -> { resolvedId = r2.data.id; detail = r2.data }
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

                    prefs.setFolderId(resolvedId)
                    // Also reflect into AppPrefs so workers and legacy paths share the same settings
                    AppPrefs.saveFolderId(getApplication(), resolvedId)
                    AppPrefs.saveEngine(getApplication(), UploadEngine.KOTLIN)
                    _status.value = "Folder OK: ${detail.name} ($resolvedId)"
                }
                is ApiResult.HttpError    -> _status.value = "Folder ${r.code}: ${r.body}"
                is ApiResult.NetworkError -> _status.value = "Folder network: ${r.exception.message}"
            }
        }
    }

    /** Create a sample text file and upload it to Drive. */
    fun uploadSampleNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val tokenProvider = CredentialManagerAuth.get(getApplication())
            val token = tokenProvider.getAccessToken()
            if (token == null) {
                _status.value = "Sign-in required"
                return@launch
            }

            val raw = folderId.value
            val parentId = DriveFolderId.extractFromUrlOrId(raw)
            if (parentId.isNullOrBlank()) {
                _status.value = "Folder URL/ID is empty"
                return@launch
            }

            val uploader = UploaderFactory.create(
                getApplication(),
                UploadEngine.KOTLIN,
                tokenProvider = tokenProvider
            ) ?: run {
                _status.value = "Uploader not available"
                return@launch
            }

            val now = System.currentTimeMillis()
            val name = "drive-sample-$now.txt"
            val text = "Hello from GeoLocationProvider @ $now\n"
            val uri = createSampleTextInDownloads(getApplication(), name, text)
                ?: run {
                    _status.value = "Failed to create sample file"
                    return@launch
                }

            when (val r = uploader.upload(uri, parentId, name)) {
                is UploadResult.Success ->
                    _status.value = "Upload OK: ${r.name}\n${r.webViewLink}"
                is UploadResult.Failure ->
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
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null

        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
            os.flush()
        } ?: return null

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    fun runBacklogNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val ctx: Context = app.applicationContext
            val zone = ZoneId.of("Asia/Tokyo")
            val today = LocalDate.now(zone)

            val samples = StorageService.getAllLocations(ctx)
            if (samples.isEmpty()) {
                _status.value = buildString {
                    append("Backup summary:\n")
                    append("No LocationSample rows in DB; nothing to back up.\n")
                    append("Backlog worker was triggered for days before today.")
                }
                MidnightExportWorker.runNow(app)
                return@launch
            }

            val firstMillis = samples.first().timeMillis
            val lastMillis = samples.last().timeMillis
            val firstDate = Instant.ofEpochMilli(firstMillis).atZone(zone).toLocalDate()
            val lastDate = Instant.ofEpochMilli(lastMillis).atZone(zone).toLocalDate()

            val exportedCount = StorageService.exportedDayCount(ctx)
            val oldestNotUploaded = StorageService.oldestNotUploadedDay(ctx)

            val sb = StringBuilder()
            sb.append("Backup summary (JST):\n")
            sb.append("Data range: ").append(firstDate).append(" to ").append(lastDate).append('\n')

            if (exportedCount <= 0L) {
                sb.append("Previous backup: none yet.\n")
            } else {
                if (oldestNotUploaded == null) {
                    val lastBackedDay = lastDate.coerceAtMost(today.minusDays(1))
                    sb.append("Previous backup: completed through ")
                        .append(lastBackedDay)
                        .append(".\n")
                } else {
                    val pendingFrom = LocalDate.ofEpochDay(oldestNotUploaded.epochDay)
                    val backedUntilInclusive = pendingFrom.minusDays(1)
                    if (backedUntilInclusive < firstDate) {
                        sb.append("Previous backup: not completed for any day yet.\n")
                    } else {
                        sb.append("Previous backup: completed through ")
                            .append(backedUntilInclusive)
                            .append(".\n")
                    }

                    val pendingTo = lastDate.coerceAtMost(today.minusDays(1))
                    if (!pendingFrom.isAfter(pendingTo)) {
                        sb.append("Pending days before today: ")
                            .append(pendingFrom)
                            .append(" to ")
                            .append(pendingTo)
                            .append(".\n")
                    } else {
                        sb.append("Pending days before today: none.\n")
                    }
                }
            }

            sb.append("Starting backlog worker for days before today.")
            _status.value = sb.toString()

            MidnightExportWorker.runNow(app)
        }
    }
}
