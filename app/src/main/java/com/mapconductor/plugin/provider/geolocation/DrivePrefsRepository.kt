package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Google Drive 関連設定の単一ソース（DataStore）。
 * - engineFlow:         NONE / KOTLIN / ...
 * - folderIdFlow:       空文字で未設定を表現（null を使わない）
 * - accountEmailFlow:   空文字で未設定
 * - tokenLastRefreshFlow: 0L で未設定
 *
 * DataStore を SoT にして、UI/Worker どちらからも同一 API で参照・更新。
 */
class DrivePrefsRepository(private val context: Context) {

    // -------- Flows (常に非nullで返す) --------
    val engineFlow: Flow<UploadEngine> =
        context.driveData.data
            .map { p ->
                p[KEY_ENGINE]?.let { runCatching { UploadEngine.valueOf(it) }.getOrElse { UploadEngine.NONE } }
                    ?: UploadEngine.NONE
            }
            .distinctUntilChanged()

    val folderIdFlow: Flow<String> =
        context.driveData.data
            .map { p -> p[KEY_FOLDER_ID].orEmpty() }
            .distinctUntilChanged()

    val accountEmailFlow: Flow<String> =
        context.driveData.data
            .map { p -> p[KEY_ACCOUNT_EMAIL].orEmpty() }
            .distinctUntilChanged()

    val tokenLastRefreshFlow: Flow<Long> =
        context.driveData.data
            .map { p -> p[KEY_TOKEN_LAST_REFRESH] ?: 0L }
            .distinctUntilChanged()

    // -------- Setters --------
    suspend fun setEngine(engine: UploadEngine) {
        context.driveData.edit { it[KEY_ENGINE] = engine.name }
    }

    /** 空/空白なら削除（= 空文字で流れてくる） */
    suspend fun setFolderId(id: String?) {
        context.driveData.edit { p ->
            val v = id?.trim().orEmpty()
            if (v.isEmpty()) p.remove(KEY_FOLDER_ID) else p[KEY_FOLDER_ID] = v
        }
    }

    suspend fun setAccountEmail(email: String) {
        context.driveData.edit { p ->
            val v = email.trim()
            if (v.isEmpty()) p.remove(KEY_ACCOUNT_EMAIL) else p[KEY_ACCOUNT_EMAIL] = v
        }
    }

    /** アクセストークンを更新できた時刻を保存（ミリ秒）。既定は現在時刻。 */
    suspend fun markTokenRefreshed(tsMillis: Long = System.currentTimeMillis()) {
        context.driveData.edit { it[KEY_TOKEN_LAST_REFRESH] = tsMillis }
    }

    // -------- DataStore 設定 --------
    private companion object {
        private val Context.driveData by preferencesDataStore(name = "drive_prefs")

        private val KEY_ENGINE = stringPreferencesKey("engine")                 // NONE / KOTLIN / ...
        private val KEY_FOLDER_ID = stringPreferencesKey("folder_id")           // "1a2B..."（空なら未設定）
        private val KEY_ACCOUNT_EMAIL = stringPreferencesKey("account_email")   // ""=未設定
        private val KEY_TOKEN_LAST_REFRESH = longPreferencesKey("token_last_refresh") // 0L=未設定
    }
}
