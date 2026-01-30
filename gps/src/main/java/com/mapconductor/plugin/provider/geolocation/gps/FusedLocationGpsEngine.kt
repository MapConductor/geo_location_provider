package com.mapconductor.plugin.provider.geolocation.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max

/**
 * GPS engine backed by FusedLocationProviderClient and GnssStatus callbacks.
 *
 * It converts platform callbacks into [GpsObservation]s and delivers them
 * to a listener on the calling thread.
 */
class FusedLocationGpsEngine(
    private val context: Context,
    private val looper: Looper
) : GpsLocationEngine {

    companion object {
        private const val TAG = "FusedLocationGpsEngine"
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationManager: LocationManager? = context.getSystemService()

    @Volatile
    private var listener: GpsLocationEngine.Listener? = null

    @Volatile
    private var isStarted: Boolean = false

    @Volatile
    private var intervalMs: Long = 30_000L

    private val gnssCallback = object : GnssStatus.Callback() {
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

            val rounded = mean?.let { kotlin.math.round(it * 10.0) / 10.0 }
            lastGnss = GnssSnapshot(
                used = used,
                total = total,
                cn0Mean = rounded?.toFloat(),
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    private data class GnssSnapshot(
        val used: Int,
        val total: Int,
        val cn0Mean: Float?,
        val timestampMs: Long
    )

    @Volatile
    private var lastGnss: GnssSnapshot? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            val now = System.currentTimeMillis()
            val gnss = lastGnss?.takeIf { snap ->
                val maxAgeMs = max(5_000L, intervalMs * 2L)
                (now - snap.timestampMs) <= maxAgeMs
            }
            val observation = GpsObservation(
                timestampMillis = loc.time.takeIf { it > 0L } ?: now,
                elapsedRealtimeNanos = runCatching { loc.elapsedRealtimeNanos }.getOrNull(),
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyM = loc.accuracy,
                speedMps = loc.speed,
                bearingDeg = loc.bearing.takeIf { loc.hasBearing() && !it.isNaN() },
                hasBearing = loc.hasBearing(),
                gnssUsed = gnss?.used,
                gnssTotal = gnss?.total,
                cn0Mean = gnss?.cn0Mean?.toDouble()
            )
            listener?.onGpsObservation(observation)
        }
    }

    override fun setListener(listener: GpsLocationEngine.Listener?) {
        this.listener = listener
    }

    override fun start() {
        if (isStarted) {
            registerGnssStatus()
            requestLocationUpdates()
            return
        }
        isStarted = true
        registerGnssStatus()
        requestLocationUpdates()
    }

    override fun stop() {
        if (!isStarted) return
        isStarted = false
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "removeLocationUpdates", t)
        }
        unregisterGnssStatus()
    }

    override fun updateInterval(intervalMs: Long) {
        this.intervalMs = intervalMs
        if (isStarted) {
            requestLocationUpdates()
        }
    }

    private fun requestLocationUpdates() {
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "removeLocationUpdates (restart)", t)
        }

        val hasFine =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "requestLocationUpdates: missing location permission")
            return
        }

        val req = LocationRequest.Builder(intervalMs)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fusedClient.requestLocationUpdates(req, locationCallback, looper)
            Log.d(TAG, "requestLocationUpdates() issued")
        } catch (se: SecurityException) {
            Log.w(TAG, "requestLocationUpdates: permission rejected", se)
        } catch (t: Throwable) {
            Log.e(TAG, "requestLocationUpdates() failed", t)
        }
    }

    private fun registerGnssStatus() {
        val mgr = locationManager ?: return
        unregisterGnssStatus()

        val hasFine =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            Log.w(TAG, "registerGnssStatus: missing ACCESS_FINE_LOCATION")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val executor = ContextCompat.getMainExecutor(context)
                mgr.registerGnssStatusCallback(executor, gnssCallback)
            } else {
                @Suppress("DEPRECATION")
                mgr.registerGnssStatusCallback(gnssCallback, Handler(looper))
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "registerGnssStatus: permission rejected", se)
        } catch (t: Throwable) {
            Log.e(TAG, "registerGnssStatus failed", t)
        }
    }

    private fun unregisterGnssStatus() {
        val mgr = locationManager ?: return
        try {
            mgr.unregisterGnssStatusCallback(gnssCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterGnssStatusCallback", t)
        }
    }
}

