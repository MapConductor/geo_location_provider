package com.mapconductor.plugin.provider.geolocation._datamanager.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine

// DataStore 名は既存と合わせてください（重複定義があれば片方に寄せる）
private val Context.dataStore by preferencesDataStore("drive_prefs")

class DrivePrefsRepository(private val context: Context) {
    // ---- Keys ----
    private val KEY_FOLDER_ID           = stringPreferencesKey("drive_folder_id")
    private val KEY_FOLDER_RESOURCE_KEY = stringPreferencesKey("drive_folder_resource_key")
    private val KEY_ACCOUNT_EMAIL       = stringPreferencesKey("drive_account_email")        // ★ 追加
    private val KEY_TOKEN_LAST_REFRESH  = longPreferencesKey("drive_token_last_refresh")     // ★ 追加
    private val KEY_ENGINE              = stringPreferencesKey("upload_engine")              // 任意（EngineをDataStoreで扱う場合）

    // ---- Flows ----
    val folderIdFlow: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[KEY_FOLDER_ID].orEmpty() }

    val folderResourceKeyFlow: Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[KEY_FOLDER_RESOURCE_KEY] }

    // ★ 追加：ViewModelが参照しているフロー
    val accountEmailFlow: Flow<String> =
        context.dataStore.data.map { prefs -> prefs[KEY_ACCOUNT_EMAIL].orEmpty() }

    val tokenLastRefreshFlow: Flow<Long> =
        context.dataStore.data.map { prefs -> prefs[KEY_TOKEN_LAST_REFRESH] ?: 0L }

    // （任意）Engine を DataStore で読みたい場合
    val engineFlow: Flow<UploadEngine> =
        context.dataStore.data.map { prefs ->
            when (prefs[KEY_ENGINE]) {
                UploadEngine.KOTLIN.name -> UploadEngine.KOTLIN
                else -> UploadEngine.NONE
            }
        }

    // ---- Setters ----
    suspend fun setFolderId(id: String) {
        context.dataStore.edit { it[KEY_FOLDER_ID] = id.trim() }
    }

    suspend fun setFolderResourceKey(rk: String?) {
        context.dataStore.edit {
            if (rk.isNullOrBlank()) it.remove(KEY_FOLDER_RESOURCE_KEY)
            else it[KEY_FOLDER_RESOURCE_KEY] = rk
        }
    }

    // ★ 追加：ViewModel が呼ぶ setter
    suspend fun setAccountEmail(email: String) {
        context.dataStore.edit { it[KEY_ACCOUNT_EMAIL] = email }
    }

    suspend fun markTokenRefreshed(nowMillis: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[KEY_TOKEN_LAST_REFRESH] = nowMillis }
    }

    // （任意）Engine を DataStore で管理する場合
    suspend fun setEngine(engine: UploadEngine) {
        context.dataStore.edit { it[KEY_ENGINE] = engine.name }
    }
}
