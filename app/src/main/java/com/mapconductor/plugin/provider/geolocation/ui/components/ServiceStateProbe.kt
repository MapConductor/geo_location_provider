package com.mapconductor.plugin.provider.geolocation.util

import android.content.*
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

object ServiceStateProbe {
    /**
     * GeoLocationService が“いま”動いているかを問い合わせる（保存しない）。
     * flags=0（AUTO_CREATE なし）なので、存在しなければ起動しません。
     */
    suspend fun isRunning(context: Context, service: Class<*>): Boolean {
        val intent = Intent(context, service)

        return withTimeout(600) {   // タイムアウトは端末差を見て 400–800ms で調整
            suspendCancellableCoroutine { cont ->
                var delivered = false
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        delivered = true
                        cont.resume(true)
                        try { context.unbindService(this) } catch (_: Exception) {}
                    }
                    override fun onNullBinding(name: ComponentName?) {
                        // FGS 等で nullBinder でも「存在」は確定
                        delivered = true
                        cont.resume(true)
                        try { context.unbindService(this) } catch (_: Exception) {}
                    }
                    override fun onServiceDisconnected(name: ComponentName?) { /* no-op */ }
                }

                // flags=0 -> 非起動バインド。存在しなければ false で即返る
                val ok = try { context.bindService(intent, conn, 0) } catch (_: Exception) { false }
                if (!ok && !delivered) cont.resume(false)

                cont.invokeOnCancellation {
                    try { context.unbindService(conn) } catch (_: Exception) {}
                }
            }
        }
    }
}
