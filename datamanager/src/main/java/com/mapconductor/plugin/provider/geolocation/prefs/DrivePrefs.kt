package com.mapconductor.plugin.provider.geolocation.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DS_NAME = "drive_prefs"

// Context extension for Drive-related DataStore; kept private to avoid collisions.
private val Context.driveDataStore by preferencesDataStore(DS_NAME)

/**
 * Thin wrapper around DataStore that exposes raw Drive preference values.
 * The repository layer is responsible for normalizing null/blank handling.
 */
internal class DrivePrefs(private val appContext: Context) {

    private object K {
        val FOLDER_ID        = stringPreferencesKey("folder_id")
        val ACCOUNT          = stringPreferencesKey("account_email")
        val ENGINE           = stringPreferencesKey("upload_engine")
        val TOKEN_TS         = longPreferencesKey("token_updated_at")
        val FOLDER_RES_KEY   = stringPreferencesKey("folder_resource_key")
        val AUTH_METHOD      = stringPreferencesKey("auth_method")
        val BACKUP_STATUS    = stringPreferencesKey("backup_status")
    }

    // ---- Read Flows ----

    val folderId: Flow<String> =
        appContext.driveDataStore.data.map { it[K.FOLDER_ID] ?: "" }

    val accountEmail: Flow<String> =
        appContext.driveDataStore.data.map { it[K.ACCOUNT] ?: "" }

    val uploadEngine: Flow<String> =
        appContext.driveDataStore.data.map { it[K.ENGINE] ?: "" }

    val authMethod: Flow<String> =
        appContext.driveDataStore.data.map { it[K.AUTH_METHOD] ?: "" }

    val tokenUpdatedAtMillis: Flow<Long> =
        appContext.driveDataStore.data.map { it[K.TOKEN_TS] ?: 0L }

    val backupStatus: Flow<String> =
        appContext.driveDataStore.data.map { it[K.BACKUP_STATUS] ?: "" }

    // resourceKey is nullable (when not yet saved).
    val folderResourceKey: Flow<String?> =
        appContext.driveDataStore.data.map { it[K.FOLDER_RES_KEY] }

    // ---- Write APIs ----

    suspend fun setFolderId(folderId: String) {
        appContext.driveDataStore.edit { it[K.FOLDER_ID] = folderId }
    }

    suspend fun setAccountEmail(email: String) {
        appContext.driveDataStore.edit { it[K.ACCOUNT] = email }
    }

    suspend fun setUploadEngine(engineName: String) {
        appContext.driveDataStore.edit { it[K.ENGINE] = engineName }
    }

    suspend fun setAuthMethod(methodName: String) {
        appContext.driveDataStore.edit { it[K.AUTH_METHOD] = methodName }
    }

    suspend fun setTokenUpdatedAt(millis: Long) {
        appContext.driveDataStore.edit { it[K.TOKEN_TS] = millis }
    }

    suspend fun setFolderResourceKey(resourceKey: String?) {
        appContext.driveDataStore.edit { prefs ->
            if (resourceKey.isNullOrBlank()) {
                prefs.remove(K.FOLDER_RES_KEY)
            } else {
                prefs[K.FOLDER_RES_KEY] = resourceKey
            }
        }
    }

    suspend fun setBackupStatus(status: String) {
        appContext.driveDataStore.edit { prefs ->
            if (status.isBlank()) {
                prefs.remove(K.BACKUP_STATUS)
            } else {
                prefs[K.BACKUP_STATUS] = status
            }
        }
    }
}
