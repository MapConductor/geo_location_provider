package com.mapconductor.plugin.provider.geolocation.auth

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.BuildConfig
import com.mapconductor.plugin.provider.geolocation.auth.appauth.AppAuthTokenProvider

/**
 * AppAuthTokenProvider のシングルトンラッパー。
 *
 * ■役割
 * - アプリ全体で共通の AppAuthTokenProvider インスタンスを提供する。
 * - clientId / redirectUri の持ち方を 1 箇所に集約する。
 *
 * ■注意
 * - clientId には「インストール アプリ」用の OAuth クライアント ID
 *   （例: APPAUTH_CLIENT_ID）を使い、Credential Manager 用の
 *   server client ID（WEB クライアント）は流用しないこと。
 */
object AppAuthAuth {

    @Volatile
    private var instance: AppAuthTokenProvider? = null

    fun get(context: Context): AppAuthTokenProvider {
        val appContext = context.applicationContext
        val cached = instance
        if (cached != null) return cached

        return synchronized(this) {
            val again = instance
            if (again != null) {
                again
            } else {
                val created = AppAuthTokenProvider(
                    context = appContext,
                    clientId = BuildConfig.APPAUTH_CLIENT_ID,
                    redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
                )
                instance = created
                created
            }
        }
    }
}

