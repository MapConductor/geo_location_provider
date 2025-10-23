package com.mapconductor.plugin.provider.geolocation.util

import android.app.ActivityManager
import android.content.Context

object ServiceStateDetector {
    /** GeoLocationService が“いま”動いているか（保存せず、その都度問い合わせ） */
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val list = am.getRunningServices(Int.MAX_VALUE) // 自アプリ分は取得可能
        return list.any { it.service.className == serviceClass.name }
    }
}
