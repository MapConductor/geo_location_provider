package com.mapconductor.plugin.provider.geolocation

import android.content.Context

/**
 * アップロード関連の現在値を“一括取得”する薄いファサード。
 * 既存コードの呼び出し（UploadPrefs.snapshot(context)）に合わせた形で提供。
 *
 * ※ ひとまず SharedPreferences("upload_prefs") を参照します。
 *    既存の保存先が DataStore/別Prefs の場合は、下の readFromSharedPrefs()
 *    を差し替える / 追記するだけで動きます（呼び出し側の変更は不要）。
 */
object UploadPrefs {

    /** 呼び出し側が扱うスナップショット型（engine と folderId だけを露出） */
    data class Snapshot(
        val engine: UploadEngine,
        val folderId: String
    )

    /**
     * 現在値を同期取得。
     * Worker（IOスレッド）や ViewModel からそのまま呼べます。
     */
    fun snapshot(context: Context): Snapshot {
        return readFromSharedPrefs(context)
    }

    // ===== 実体: まずは SharedPreferences から読み取る実装 =====

    private const val SP_NAME = "upload_prefs"
    private const val KEY_ENGINE = "engine"          // 例: "NONE" / "KOTLIN"
    private const val KEY_FOLDER_ID = "folder_id"    // 例: "1a2B3c..." or full URL

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

    // ---（必要なら今後ここに DataStore/Repository からの読み出しを追加）---
    // 例：
    // private fun readFromDataStore(context: Context): Snapshot { ... }
    // private fun readFromRepository(context: Context): Snapshot { ... }
}
