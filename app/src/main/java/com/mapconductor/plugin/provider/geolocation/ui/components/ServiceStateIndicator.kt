package com.mapconductor.plugin.provider.geolocation.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.mapconductor.plugin.provider.geolocation.service.GeoLocationService
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

object ServiceStateIndicator {

    /**
     * GeoLocationService が「いま位置更新＋DRを動かしているか」を問い合わせる。
     *
     * 判定ロジック:
     * - bindService(flags=0) で一時的にバインドを試みる。
     * - 成功した場合:
     *     - binder が GeoLocationService.LocalBinder なら、
     *       service.isLocationRunning() の結果を返す。
     *     - それ以外の binder（想定外）の場合は「サービスは存在する」とみなし true。
     * - bindService が失敗した場合:
     *     - サービスインスタンスが存在しないとみなし false。
     *
     * 注意:
     * - flags=0（BIND_AUTO_CREATE なし）なので、存在しないサービスを勝手に起動はしない。
     */
    suspend fun isRunning(context: Context, service: Class<*>): Boolean {
        val intent = Intent(context, service)

        return withTimeout(600) {
            suspendCancellableCoroutine { cont ->
                var delivered = false

                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        delivered = true

                        val running = when (binder) {
                            is GeoLocationService.LocalBinder -> {
                                try {
                                    binder.getService().isLocationRunning()
                                } catch (_: Throwable) {
                                    // 何かおかしくても「サービスは存在する」扱い
                                    true
                                }
                            }
                            else -> {
                                // 想定外の binder 型: サービスは存在しているので true 扱い
                                true
                            }
                        }

                        cont.resume(running)
                        try {
                            context.unbindService(this)
                        } catch (_: Exception) {
                        }
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        // サービスインスタンスは存在するので true 扱い
                        delivered = true
                        cont.resume(true)
                        try {
                            context.unbindService(this)
                        } catch (_: Exception) {
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        // 一時的な切断なのでここでは何もしない
                    }
                }

                // flags = 0 -> 非起動バインド。存在しなければ false で即返る。
                val ok = try {
                    context.bindService(intent, conn, 0)
                } catch (_: Exception) {
                    false
                }

                if (!ok && !delivered) {
                    cont.resume(false)
                }

                cont.invokeOnCancellation {
                    try {
                        context.unbindService(conn)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
