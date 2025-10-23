package com.mapconductor.plugin.provider.geolocation.prefs

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine

/**
 * 設定の“読み出し統合”ファサード（core 側）。
 * datamanager の DataStore には依存せず、SharedPreferences のみを参照します。
 * - engine : SharedPreferences("upload_prefs") から読む/書く
 * - folderId : SharedPreferences から読む（URLが保存されていても ID に正規化）
 *
 * DataStore を優先したい UI 側（app/_datamanager）は、別途 DrivePrefsRepository を使って
 * 値を保存してください。ここでは「現在の値を使って処理する」用途に十分な最小実装に留めます。
 */
object AppPrefs {

    data class Snapshot(
        val engine: UploadEngine,
        val folderId: String
    )

    fun snapshot(context: Context): Snapshot {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val engine = run {
            val s = sp.getString(KEY_ENGINE, null)
            UploadEngine.fromString(s)
        }
        val rawFolder = sp.getString(KEY_FOLDER_ID, "").orEmpty()
        val folderId = normalizeFolderId(rawFolder)
        return Snapshot(engine = engine, folderId = folderId)
    }

    fun saveEngine(context: Context, engine: UploadEngine) {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_ENGINE, engine.name).apply()
    }

    fun saveFolderId(context: Context, idOrUrl: String) {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_FOLDER_ID, idOrUrl).apply()
    }

    // /drive/folders/{id} の {id} を抜き出す（Google Drive 共有URL用）
    private fun normalizeFolderId(idOrUrl: String): String {
        val raw = idOrUrl.trim()
        if (raw.isEmpty()) return ""
        val m = FOLDER_URL_REGEX.find(raw)
        if (m != null && m.groupValues.size >= 2) {
            return m.groupValues[1]
        }
        return raw
    }

    private val FOLDER_URL_REGEX by lazy {
        Regex("""/folders/([a-zA-Z0-9_\-]{10,})""")
    }

    // ---- SharedPreferences ----
    private const val SP_NAME = "upload_prefs"
    private const val KEY_ENGINE = "engine"
    private const val KEY_FOLDER_ID = "folder_id"
}
