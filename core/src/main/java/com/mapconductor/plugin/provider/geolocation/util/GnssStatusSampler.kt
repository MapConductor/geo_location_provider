package com.mapconductor.plugin.provider.geolocation.util

import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlin.math.round

/**
 * Samples GNSS status and computes used/total satellite counts and mean C/N0.
 *
 * It focuses on satellites with usedInFix set to true and falls back to all
 * satellites when there are no usedInFix samples with valid C/N0.
 *
 * Requires ACCESS_FINE_LOCATION permission.
 */
internal class GnssStatusSampler(
    private val context: Context
) {
    data class Stats(
        val used: Int,
        val total: Int,
        val cn0Mean: Float?,          // dB-Hz
        val timestampMs: Long
    )

    private val lm: LocationManager? = context.getSystemService()
    @Volatile
    private var last: Stats? = null

    private val cb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val total = status.satelliteCount
            var used = 0
            var sum = 0.0
            var cnt = 0
            for (i in 0 until total) {
                val cn0 = status.getCn0DbHz(i).toDouble()
                if (status.usedInFix(i)) {
                    used++
                    if (cn0 > 0.0) {
                        sum += cn0
                        cnt++
                    }
                }
            }

            // If we could not compute mean C/N0 from usedInFix satellites, fall back to all
            var mean: Double? = if (cnt > 0) sum / cnt else null
            if (mean == null && total > 0) {
                var s = 0.0
                var c = 0
                for (i in 0 until total) {
                    val cn0 = status.getCn0DbHz(i).toDouble()
                    if (cn0 > 0.0) {
                        s += cn0
                        c++
                    }
                }
                mean = if (c > 0) s / c else null
            }

            val rounded = mean?.let { (round(it * 10.0) / 10.0).toFloat() }
            last = Stats(
                used = used,
                total = total,
                cn0Mean = rounded,
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * Starts listening for GNSS status updates.
     *
     * @param looper used only on API < 30 when registering with a Handler;
     *               null means mainLooper.
     */
    fun start(looper: Looper? = null) {
        val mgr = lm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: register with Executor based callback
            val executor = ContextCompat.getMainExecutor(context)
            mgr.registerGnssStatusCallback(executor, cb)
        } else {
            // API 29 and below: use Handler(Looper)
            @Suppress("DEPRECATION")
            mgr.registerGnssStatusCallback(cb, Handler(looper ?: Looper.getMainLooper()))
        }
    }

    fun stop() {
        lm?.unregisterGnssStatusCallback(cb)
    }

    fun snapshot(): Stats? = last
}

