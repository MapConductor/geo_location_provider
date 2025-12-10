package com.mapconductor.plugin.provider.geolocation.prefs

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wrapper around DrivePrefs that provides a UI/UseCase-friendly view of Drive settings.
 *
 * - Normalizes null / blank handling in this layer.
 * - ViewModels and UseCases should generally read Drive settings through this repository.
 */
class DrivePrefsRepository(context: Context) {

    private val prefs = DrivePrefs(context)

    // ---- Read Flows ----

    /** Drive folder ID (empty string when not set). */
    val folderIdFlow: Flow<String> = prefs.folderId

    /** Folder resourceKey (null when not set or blank). */
    val folderResourceKeyFlow: Flow<String?> =
        prefs.folderResourceKey.map { it?.takeIf { s -> s.isNotBlank() } }

    /** Signed-in account email (empty string when not set). */
    val accountEmailFlow: Flow<String> = prefs.accountEmail

    /** Upload engine name (empty string when not set). */
    val uploadEngineNameFlow: Flow<String> = prefs.uploadEngine

    /** Auth method name (Credential Manager / AppAuth); empty string when not set. */
    val authMethodFlow: Flow<String> = prefs.authMethod

    /** Last token refresh timestamp in milliseconds; 0L when never refreshed. */
    val tokenUpdatedAtMillisFlow: Flow<Long> = prefs.tokenUpdatedAtMillis

    /** Latest backup progress/status message (empty string when not set). */
    val backupStatusFlow: Flow<String> = prefs.backupStatus

    // --- Derived helper flows ---

    /** Alias for tokenUpdatedAtMillisFlow for ViewModel convenience. */
    val tokenLastRefreshFlow: Flow<Long> = tokenUpdatedAtMillisFlow

    // ---- Write APIs ----

    suspend fun setFolderId(folderId: String) = prefs.setFolderId(folderId)

    suspend fun setFolderResourceKey(resourceKey: String?) = prefs.setFolderResourceKey(resourceKey)

    suspend fun setAccountEmail(email: String) = prefs.setAccountEmail(email)

    suspend fun setUploadEngine(engineName: String) = prefs.setUploadEngine(engineName)

    suspend fun setAuthMethod(methodName: String) = prefs.setAuthMethod(methodName)

    suspend fun setTokenUpdatedAt(millis: Long) = prefs.setTokenUpdatedAt(millis)

    suspend fun setBackupStatus(status: String) = prefs.setBackupStatus(status)

    // --- Derived write helpers ---

    /** Convenience: mark token refreshed at given time (millis). */
    suspend fun markTokenRefreshed(nowMillis: Long) = prefs.setTokenUpdatedAt(nowMillis)

    /** Convenience: mark token refreshed at current system time. */
    suspend fun markTokenRefreshed() =
        prefs.setTokenUpdatedAt(System.currentTimeMillis())
}
