package com.mapconductor.plugin.provider.geolocation.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mapconductor.plugin.provider.geolocation.R

/** 通知の実装を一箇所に集約 */
object NotificationHelper {
    private const val CHANNEL_ID = "glp_export"
    private const val CHANNEL_NAME = "GeoLocationProvider Export"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }
        }
    }

    /** 失敗通知 */
    fun notifyPermanentFailure(context: Context, message: String) {
        ensureChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 適宜差し替え
            .setContentTitle("Export/Upload Failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1001, notif)
    }
}

/** 既存互換：古い呼び出しはそのまま動く */
@Deprecated("Use NotificationHelper instead")
object ExportNotify {
    fun notifyPermanentFailure(context: Context, message: String) {
        NotificationHelper.notifyPermanentFailure(context, message)
    }
}
