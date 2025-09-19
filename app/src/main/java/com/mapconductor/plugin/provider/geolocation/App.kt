package com.mapconductor.plugin.provider.geolocation

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // ★ 起動時にネイティブ関数をコール
        val msg = NativeBridge.concatWorld("Hello")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

        // アプリ起動時に前景サービスを開始
        val intent = Intent(this, GeoLocationService::class.java)
        ContextCompat.startForegroundService(this, intent)
        MidnightExportScheduler.scheduleNext(this)
    }
}
