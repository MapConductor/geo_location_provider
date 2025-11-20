package com.mapconductor.plugin.provider.geolocation

import android.app.Application
import com.mapconductor.plugin.provider.geolocation.auth.CredentialManagerAuth
import com.mapconductor.plugin.provider.geolocation.drive.auth.DriveTokenProviderRegistry
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportScheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        DriveTokenProviderRegistry.registerBackgroundProvider(
            CredentialManagerAuth.get(this)
        )

        MidnightExportScheduler.scheduleNext(this)

        // サービスの起動は UI 側（権限許諾後）で行う
    }
}

