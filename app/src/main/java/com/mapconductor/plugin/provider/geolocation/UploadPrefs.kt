package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull   // ★ 追加

import java.io.IOException

/**
 * このモジュールは「アップロード関連の設定」に限定した独立DataStoreです。
 * 既存のDataStoreがあっても「uploader_prefs」という別ファイル名で衝突しません。
 */

// Context 拡張: DataStore 本体
private val Context.uploaderDataStore by preferencesDataStore(name = "uploader_prefs")

object UploadPrefs {
    // Keys
    private val KEY_ENGINE = stringPreferencesKey("upload_engine")     // "none" | "kotlin" | "jni" | "native"
    private val KEY_FOLDER = stringPreferencesKey("drive_folder_id")   // Google Drive のフォルダID

    // 公開: Flow
    fun engineFlow(context: Context): Flow<UploadEngine> =
        context.uploaderDataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs ->
                UploadEngine.fromString(prefs[KEY_ENGINE])
            }

    fun folderIdFlow(context: Context): Flow<String?> =
        context.uploaderDataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs ->
                val raw = prefs[KEY_FOLDER]
                if (raw.isNullOrBlank()) null else raw
            }

    // 公開: セッター
    suspend fun setEngine(context: Context, engine: UploadEngine) {
        context.uploaderDataStore.edit { prefs ->
            prefs[KEY_ENGINE] = engine.wire
        }
    }

    suspend fun setFolderId(context: Context, folderId: String?) {
        context.uploaderDataStore.edit { prefs ->
            if (folderId.isNullOrBlank()) {
                prefs.remove(KEY_FOLDER)
            } else {
                prefs[KEY_FOLDER] = folderId
            }
        }
    }

    // 便宜: 現値を1回だけ同期取得したいときのヘルパ（UI外の場所で使う想定）
    suspend fun readSnapshot(context: Context): Snapshot =
        Snapshot(
            engine = engineFlow(context).firstOrNullSafe() ?: UploadEngine.NONE,
            folderId = folderIdFlow(context).firstOrNullSafe()
        )

    data class Snapshot(
        val engine: UploadEngine,
        val folderId: String?,
    )
}

// 小さなFlowヘルパ（依存を増やさずに済ませる）
private suspend fun <T> Flow<T>.firstOrNullSafe(): T? =
    try { this.firstOrNull() } catch (_: Throwable) { null }
