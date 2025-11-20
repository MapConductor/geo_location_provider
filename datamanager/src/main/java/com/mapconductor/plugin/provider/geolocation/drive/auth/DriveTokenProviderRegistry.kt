package com.mapconductor.plugin.provider.geolocation.drive.auth

/**
 * 背景スレッド（Worker など）から利用する GoogleDriveTokenProvider の登録・取得用レジストリ。
 *
 * ホストアプリ側で、起動時などに Credential Manager / AppAuth 実装を登録しておき、
 * datamanager 側はこのレジストリ経由でトークンプロバイダを取得する。
 *
 * - UI を伴うフローはホストアプリの責務とし、ここには渡さない前提。
 * - provider はアプリプロセス内で共有されるシングルトン的な扱い。
 */
object DriveTokenProviderRegistry {

    @Volatile
    private var backgroundProvider: GoogleDriveTokenProvider? = null

    /**
     * バックグラウンド処理で使用するトークンプロバイダを登録する。
     * null を渡すと登録をクリアする。
     */
    fun registerBackgroundProvider(provider: GoogleDriveTokenProvider?) {
        backgroundProvider = provider
    }

    /**
     * 登録済みのバックグラウンド用トークンプロバイダを返す。
     * 未登録の場合は null。
     */
    fun getBackgroundProvider(): GoogleDriveTokenProvider? = backgroundProvider
}

