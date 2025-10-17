package com.mapconductor.plugin.provider.geolocation._core.prefs

import android.content.Context
import com.mapconductor.plugin.provider.geolocation._datamanager.prefs.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import kotlinx.coroutines.flow.firstOrNull

/**
 * 設定の“読み出し統合”用ファサード。
 * - フォルダIDは DataStore(DrivePrefsRepository) を優先、空なら SharedPreferences(UploadPrefs) をフォールバック。
 * - engine は SharedPreferences(UploadPrefs) から読む。
 * - フォルダ URL が渡されても ID に正規化して返す。
 */
object AppPrefs {

    data class Snapshot(
        val engine: UploadEngine,
        val folderId: String,   // 常に「フォルダID（URLでなくID）」へ正規化済み
    )

    /**
     * Upload 関連の集約スナップショット取得。
     * suspend: DataStore 読み取りのため。
     */
    suspend fun uploadSnapshot(context: Context): Snapshot {
        val dsRepo = DrivePrefsRepository(context)
        val dataStoreFolderRaw = dsRepo.folderIdFlow.firstOrNull().orEmpty()
        val spSnapshot = UploadPrefs.snapshot(context)

        // DataStore が空なら SharedPreferences 側をフォールバック
        val chosenFolderRaw = if (dataStoreFolderRaw.isNotBlank()) dataStoreFolderRaw else spSnapshot.folderId
        val normalizedFolderId = normalizeFolderId(chosenFolderRaw)

        // engine は SharedPreferences 版をソースオブトゥルース
        val engine = spSnapshot.engine

        return Snapshot(engine = engine, folderId = normalizedFolderId)
    }

    /**
     * Google Drive フォルダ URL/共有リンク/ID のいずれかが来ても「フォルダID」に正規化する。
     * 例:
     *  - https://drive.google.com/drive/folders/1oIp0kvwM1_R3FHIsyIro236wsln7Kebv
     *  - https://drive.google.com/drive/u/0/folders/1oIp0kvwM1_R3FHIsyIro236wsln7Kebv?xxx=yyy
     *  - 1oIp0kvwM1_R3FHIsyIro236wsln7Kebv  (そのままID)
     */
    private fun normalizeFolderId(idOrUrl: String): String {
        val raw = idOrUrl.trim()
        if (raw.isEmpty()) return ""

        // URL から /folders/{id} を抜き出す
        val m = FOLDER_URL_REGEX.find(raw)
        if (m != null && m.groupValues.size >= 2) {
            return m.groupValues[1]
        }
        // URL 形式でなく “ID 単体” と見なせる場合はそのまま返す
        return raw
    }

    // /drive/folders/{id} の {id} を抜き出す（Google Drive 共有URL用）
    private val FOLDER_URL_REGEX by lazy {
        // ID は英数, -, _ を含むことがあるため広めに許容（最低10文字程度）
        Regex("""/folders/([a-zA-Z0-9_\-]{10,})""")
    }
}

/**
 * 既存の UploadPrefs（SharedPreferences 版）
 * - engine と folderId を保持（後方互換のため存置）
 * - 現在は engine のソースオブトゥルースとして利用
 */
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
    private const val KEY_ENGINE = "engine"        // "NONE" / "KOTLIN" / …
    private const val KEY_FOLDER_ID = "folder_id"  // "1a2B..." or URL（古い実装ではURLが保存されている可能性あり）

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
