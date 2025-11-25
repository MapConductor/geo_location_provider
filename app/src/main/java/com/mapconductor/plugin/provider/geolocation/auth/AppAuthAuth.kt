package com.mapconductor.plugin.provider.geolocation.auth

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.BuildConfig
import com.mapconductor.plugin.provider.geolocation.auth.appauth.AppAuthTokenProvider

/**
 * Singleton wrapper around AppAuthTokenProvider.
 *
 * Responsibilities:
 * - Provide a single shared AppAuthTokenProvider instance for the whole app.
 * - Centralize how clientId and redirectUri are wired.
 *
 * Notes:
 * - clientId must be an "installed app" OAuth client id (APPAUTH_CLIENT_ID),
 *   not the server client id used for Credential Manager.
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

