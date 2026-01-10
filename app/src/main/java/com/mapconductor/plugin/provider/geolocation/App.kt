package com.mapconductor.plugin.provider.geolocation

import android.app.Application
import com.mapconductor.plugin.provider.geolocation.auth.CredentialManagerAuth
import com.mapconductor.plugin.provider.geolocation.auth.AppAuthAuth
import com.mapconductor.plugin.provider.geolocation.drive.auth.DriveTokenProviderRegistry
import com.mapconductor.plugin.provider.geolocation.fusion.ImsEkf
import com.mapconductor.plugin.provider.geolocation.prefs.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportScheduler
import com.mapconductor.plugin.provider.geolocation.work.RealtimeUploadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Default to Credential Manager for background uploads.
        DriveTokenProviderRegistry.registerBackgroundProvider(
            CredentialManagerAuth.get(this)
        )

        ImsEkf.install(this)

        // Adjust background provider based on stored auth method.
        appScope.launch {
            val prefs = DrivePrefsRepository(this@App)
            val method = runCatching { prefs.authMethodFlow.first() }
                .getOrNull()
                .orEmpty()
            when (method) {
                "appauth" -> {
                    val provider = AppAuthAuth.get(this@App)
                    val ok = runCatching { provider.isAuthenticated() }.getOrElse { false }
                    if (ok) {
                        DriveTokenProviderRegistry.registerBackgroundProvider(provider)
                    }
                }
                "credential_manager", "" -> {
                    DriveTokenProviderRegistry.registerBackgroundProvider(
                        CredentialManagerAuth.get(this@App)
                    )
                }
            }
        }

        MidnightExportScheduler.scheduleNext(this)
        RealtimeUploadManager.start(this)
    }
}
