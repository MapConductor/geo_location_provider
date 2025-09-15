package com.mapconductor.plugin.provider.geolocation

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // アプリ起動時に前景サービスを開始
        val intent = Intent(this, GeoLocationService::class.java)
        ContextCompat.startForegroundService(this, intent)
        MidnightExportScheduler.scheduleNext(this)
    }
}
