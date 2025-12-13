package com.mapconductor.plugin.provider.geolocation

import android.app.Application
import com.mapconductor.plugin.provider.geolocation.auth.CredentialManagerAuth
import com.mapconductor.plugin.provider.geolocation.drive.auth.DriveTokenProviderRegistry
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportScheduler
import com.mapconductor.plugin.provider.geolocation.work.RealtimeUploadManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        DriveTokenProviderRegistry.registerBackgroundProvider(
            CredentialManagerAuth.get(this)
        )

        MidnightExportScheduler.scheduleNext(this)
        RealtimeUploadManager.start(this)
    }
}
