package com.mapconductor.plugin.provider.geolocation.core.data.prefs

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine

/**
 * 設定の“読み出し統合”用ファサード。
 * まずは Upload 関連だけ UploadPrefs から橋渡し。
 */
object AppPrefs {

    data class Upload(
        val engine: UploadEngine,
        val folderId: String
    )

    /** 現状は UploadPrefs をそのまま委譲。後で DataStore 等へ差し替え可能。 */
    fun uploadSnapshot(context: Context): Upload {
        val up = UploadPrefs.snapshot(context)
        return Upload(engine = up.engine, folderId = up.folderId)
    }
}

/** 既存の UploadPrefs（SharedPreferences 版） */
object UploadPrefs {

    data class Snapshot(
        val engine: UploadEngine,
        val folderId: String
    )

    fun snapshot(context: Context): Snapshot = readFromSharedPrefs(context)

    fun setFolderId(context: Context, id: String) {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_FOLDER_ID, id.trim()).apply()
    }

    fun setEngine(context: Context, engine: UploadEngine) {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_ENGINE, engine.name).apply()
    }

    // ---- 実体：SharedPreferences ----
    private const val SP_NAME = "upload_prefs"
    private const val KEY_ENGINE = "engine"        // "NONE" / "KOTLIN"
    private const val KEY_FOLDER_ID = "folder_id"  // "1a2B..." or URL

    private fun readFromSharedPrefs(context: Context): Snapshot {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

        val engineStr = sp.getString(KEY_ENGINE, null)
        val engine = try {
            if (engineStr.isNullOrBlank()) UploadEngine.NONE
            else UploadEngine.valueOf(engineStr)
        } catch (_: Throwable) {
            UploadEngine.NONE
        }

        val folderId = sp.getString(KEY_FOLDER_ID, "") ?: ""
        return Snapshot(engine = engine, folderId = folderId)
    }
}
