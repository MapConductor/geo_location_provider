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
     * Checks whether GeoLocationService is currently running location/DR updates.
     *
     * Logic:
     * - Try a temporary bindService(flags = 0).
     * - If bind succeeds:
     *   - When binder is GeoLocationService.LocalBinder, return service.isLocationRunning().
     *   - Otherwise treat it as "service exists" and return true.
     * - If bind fails:
     *   - Treat it as "service instance does not exist" and return false.
     *
     * Note:
     * - flags = 0 (no BIND_AUTO_CREATE) so this will not start the service if it is not running.
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
                                    // If something is wrong, still treat as "service exists"
                                    true
                                }
                            }
                            else -> {
                                // Unexpected binder type but service exists, treat as running
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
                        // Service instance exists; treat as running
                        delivered = true
                        cont.resume(true)
                        try {
                            context.unbindService(this)
                        } catch (_: Exception) {
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        // Temporary disconnect; nothing to do here
                    }
                }

                // flags = 0 -> do not auto-create; if service does not exist, this returns false
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

