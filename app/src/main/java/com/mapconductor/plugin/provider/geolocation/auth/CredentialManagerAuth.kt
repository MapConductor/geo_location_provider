package com.mapconductor.plugin.provider.geolocation.auth

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.BuildConfig
import com.mapconductor.plugin.provider.geolocation.auth.credentialmanager.CredentialManagerTokenProvider

object CredentialManagerAuth {

    @Volatile
    private var instance: CredentialManagerTokenProvider? = null

    fun get(context: Context): CredentialManagerTokenProvider {
        val appContext = context.applicationContext
        val current = instance
        if (current != null) return current
        return synchronized(this) {
            val again = instance
            if (again != null) {
                again
            } else {
                val created = CredentialManagerTokenProvider(
                    context = appContext,
                    serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
                )
                instance = created
                created
            }
        }
    }
}

