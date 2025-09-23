package com.mapconductor.plugin.provider.geolocation

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        MidnightExportScheduler.scheduleNext(this)

        // サービスの起動は UI 側（権限許諾後）で行う
    }
}
