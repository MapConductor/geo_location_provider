package com.mapconductor.plugin.provider.geolocation.auth

import android.content.Context
import android.util.Log
import com.mapconductor.plugin.provider.geolocation.BuildConfig
import com.mapconductor.plugin.provider.geolocation.auth.credentialmanager.CredentialManagerTokenProvider

/**
 * Simple singleton holder for [CredentialManagerTokenProvider].
 *
 * The provider needs the application context + server client ID, so we create
 * it lazily and reuse it across the app (Activities, Workers, etc.).
 */
object DriveCredentialManagerHolder {
    private const val TAG = "DriveAuthHolder"

    @Volatile
    private var instance: CredentialManagerTokenProvider? = null

    fun get(context: Context): CredentialManagerTokenProvider {
        val appContext = context.applicationContext
        val existing = instance
        if (existing != null) return existing

        val serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
        if (serverClientId.isBlank()) {
            Log.w(TAG, "CREDENTIAL_MANAGER_SERVER_CLIENT_ID is blank. Check secrets.properties.")
        }

        return CredentialManagerTokenProvider(
            context = appContext,
            serverClientId = serverClientId
        ).also { created ->
            instance = created
        }
    }
}
