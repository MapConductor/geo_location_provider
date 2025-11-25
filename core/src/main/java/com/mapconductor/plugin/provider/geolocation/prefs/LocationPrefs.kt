package com.mapconductor.plugin.provider.geolocation.prefs

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine

/**
 * Legacy SharedPreferences based settings for upload engine and folder id.
 *
 * Responsibilities:
 * - Keep UploadEngine in SharedPreferences("upload_prefs").
 * - Keep the Drive folder id (or URL) and normalize it to a plain folder id.
 *
 * New UI code should prefer the DataStore based DrivePrefsRepository in the
 * datamanager module and use AppPrefs only as a compatibility layer for workers
 * and older code paths.
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

    // Extract {id} from /drive/folders/{id} style Google Drive URLs
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

    // SharedPreferences keys
    private const val SP_NAME = "upload_prefs"
    private const val KEY_ENGINE = "engine"
    private const val KEY_FOLDER_ID = "folder_id"
}

