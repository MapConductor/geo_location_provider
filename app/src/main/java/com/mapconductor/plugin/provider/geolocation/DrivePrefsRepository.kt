package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.driveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drive_prefs"
)

class DrivePrefsRepository(private val context: Context) {
    object Keys {
        val FOLDER_ID = stringPreferencesKey("drive.folderId")
        val ACCOUNT_EMAIL = stringPreferencesKey("drive.accountEmail")
        val SCOPES_GRANTED = stringSetPreferencesKey("drive.scopesGranted")
        val TOKEN_LAST_REFRESH = longPreferencesKey("drive.tokenLastRefreshMillis")
    }

    val folderIdFlow: Flow<String> = context.driveDataStore.data.map { it[Keys.FOLDER_ID] ?: "" }
    val accountEmailFlow: Flow<String> = context.driveDataStore.data.map { it[Keys.ACCOUNT_EMAIL] ?: "" }
    val scopesGrantedFlow: Flow<Set<String>> = context.driveDataStore.data.map { it[Keys.SCOPES_GRANTED] ?: emptySet() }
    val tokenLastRefreshFlow: Flow<Long> = context.driveDataStore.data.map { it[Keys.TOKEN_LAST_REFRESH] ?: 0L }

    suspend fun setFolderId(id: String) {
        context.driveDataStore.edit { it[Keys.FOLDER_ID] = id.trim() }
    }

    suspend fun setAccountEmail(email: String) {
        context.driveDataStore.edit { it[Keys.ACCOUNT_EMAIL] = email }
    }

    suspend fun setScopesGranted(scopes: Set<String>) {
        context.driveDataStore.edit { it[Keys.SCOPES_GRANTED] = scopes }
    }

    suspend fun markTokenRefreshed(epochMillis: Long) {
        context.driveDataStore.edit { it[Keys.TOKEN_LAST_REFRESH] = epochMillis }
    }
}
