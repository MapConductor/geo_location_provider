package com.mapconductor.plugin.provider.geolocation.prefs

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * UI/UseCase 側が使いやすいように null/blank の扱いを吸収するレイヤ。
 * DriveUploader や ViewModel は基本こちらを参照する。
 */
class DrivePrefsRepository(context: Context) {

    private val prefs = DrivePrefs(context)

    // ---- Read Flows ----
    /** Drive フォルダID（未設定なら空文字） */
    val folderIdFlow: Flow<String> = prefs.folderId

    /** フォルダの resourceKey（未設定/空文字なら null を返す） */
    val folderResourceKeyFlow: Flow<String?> =
        prefs.folderResourceKey.map { it?.takeIf { s -> s.isNotBlank() } }

    /** サインイン中アカウント（未設定なら空文字） */
    val accountEmailFlow: Flow<String> = prefs.accountEmail

    /** アップロードエンジン名（未設定なら空文字） */
    val uploadEngineNameFlow: Flow<String> = prefs.uploadEngine

    /** 最終トークン更新時刻（ミリ秒）。未保存なら 0L */
    val tokenUpdatedAtMillisFlow: Flow<Long> = prefs.tokenUpdatedAtMillis

    // --- 既存コード互換エイリアス ---
    /** 互換用: ViewModel 等が参照する想定の名前 */
    val tokenLastRefreshFlow: Flow<Long> = tokenUpdatedAtMillisFlow

    // ---- Write APIs ----
    suspend fun setFolderId(folderId: String) = prefs.setFolderId(folderId)

    suspend fun setFolderResourceKey(resourceKey: String?) = prefs.setFolderResourceKey(resourceKey)

    suspend fun setAccountEmail(email: String) = prefs.setAccountEmail(email)

    suspend fun setUploadEngine(engineName: String) = prefs.setUploadEngine(engineName)

    suspend fun setTokenUpdatedAt(millis: Long) = prefs.setTokenUpdatedAt(millis)

    // --- 既存コード互換エイリアス ---
    /** 互換用: nowMillis を保存する */
    suspend fun markTokenRefreshed(nowMillis: Long) = prefs.setTokenUpdatedAt(nowMillis)

    /** 互換用オーバーロード: 現在時刻で更新 */
    suspend fun markTokenRefreshed() = prefs.setTokenUpdatedAt(System.currentTimeMillis())
}
