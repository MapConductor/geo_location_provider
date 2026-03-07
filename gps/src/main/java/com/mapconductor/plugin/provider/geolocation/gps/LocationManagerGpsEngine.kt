package com.mapconductor.plugin.provider.geolocation.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlin.math.max

/**
 * GPS engine backed by the platform LocationManager.
 *
 * This is intended for environments where Google Play services are not present.
 */
class LocationManagerGpsEngine(
    private val context: Context,
    private val looper: Looper
) : GpsLocationEngine {

    companion object {
        private const val TAG = "LocationManagerGpsEngine"
        private const val NETWORK_ACCEPT_IF_NO_GPS_MS_MIN = 12_000L
    }

    private val locationManager: LocationManager? = context.getSystemService()

    @Volatile
    private var listener: GpsLocationEngine.Listener? = null

    @Volatile
    private var isStarted: Boolean = false

    @Volatile
    private var intervalMs: Long = 30_000L

    private val activeProviders: MutableSet<String> = LinkedHashSet()

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
    @Volatile
    private var lastAcceptedTimestampMillis: Long = 0L
    @Volatile
    private var lastAcceptedElapsedRealtimeNanos: Long? = null
    @Volatile
    private var lastAcceptedGpsTimestampMillis: Long = 0L
    @Volatile
    private var lastAcceptedGpsElapsedRealtimeNanos: Long? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
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
        unregisterGnssStatus()
        stopLocationUpdates()
        resetAcceptanceState()
    }

    override fun updateInterval(intervalMs: Long) {
        this.intervalMs = intervalMs
        if (isStarted) {
            requestLocationUpdates()
        }
    }

    private fun handleLocation(loc: Location) {
        val now = System.currentTimeMillis()
        val elapsedRealtimeNanos = runCatching { loc.elapsedRealtimeNanos }.getOrNull()
        val locationTimestampMillis = loc.time.takeIf { it > 0L } ?: now
        val sourceProvider = classifySourceProvider(loc.provider)
        if (shouldSuppressNetworkObservation(sourceProvider, now, elapsedRealtimeNanos)) {
            Log.d(
                TAG,
                "drop network location while GPS is recent ts=$locationTimestampMillis now=$now"
            )
            return
        }
        if (isStaleLocation(locationTimestampMillis, elapsedRealtimeNanos, now)) {
            Log.d(
                TAG,
                "drop stale location provider=${loc.provider} ts=$locationTimestampMillis now=$now"
            )
            return
        }

        val gnss = lastGnss?.takeIf { snap ->
            val maxAgeMs = max(5_000L, intervalMs * 2L)
            (now - snap.timestampMs) <= maxAgeMs
        }
        val observationTimestampMillis = locationTimestampMillis.coerceAtMost(now + 1_000L)
        val observation = GpsObservation(
            timestampMillis = observationTimestampMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyM = loc.accuracy,
            speedMps = loc.speed,
            bearingDeg = loc.bearing.takeIf { loc.hasBearing() && !it.isNaN() },
            hasBearing = loc.hasBearing(),
            gnssUsed = gnss?.used,
            gnssTotal = gnss?.total,
            cn0Mean = gnss?.cn0Mean?.toDouble(),
            sourceProvider = sourceProvider
        )
        lastAcceptedTimestampMillis = observationTimestampMillis
        lastAcceptedElapsedRealtimeNanos = elapsedRealtimeNanos
        if (sourceProvider == GpsSourceProvider.GPS) {
            lastAcceptedGpsTimestampMillis = observationTimestampMillis
            lastAcceptedGpsElapsedRealtimeNanos = elapsedRealtimeNanos
        }
        listener?.onGpsObservation(observation)
    }

    private fun requestLocationUpdates() {
        val mgr = locationManager
        if (mgr == null) {
            Log.w(TAG, "requestLocationUpdates: LocationManager unavailable")
            return
        }
        stopLocationUpdates()

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

        val preferredCandidates: List<String> =
            if (hasFine) {
                listOf(LocationManager.GPS_PROVIDER)
            } else {
                listOf(LocationManager.NETWORK_PROVIDER)
            }
        val fallbackCandidates: List<String> =
            if (hasFine && hasCoarse) {
                listOf(LocationManager.NETWORK_PROVIDER)
            } else {
                emptyList()
            }

        val preferredEnabled: List<String> = preferredCandidates.filter { provider ->
            runCatching { mgr.isProviderEnabled(provider) }.getOrDefault(false)
        }
        val fallbackEnabled: List<String> = fallbackCandidates.filter { provider ->
            runCatching { mgr.isProviderEnabled(provider) }.getOrDefault(false)
        }
        val selected = LinkedHashSet<String>()
        selected.addAll(preferredEnabled)
        selected.addAll(fallbackEnabled)
        val chosen: List<String> =
            selected.toList()

        if (chosen.isEmpty()) {
            Log.w(TAG, "requestLocationUpdates: no providers available")
            return
        }

        for (provider in chosen) {
            try {
                @Suppress("MissingPermission")
                mgr.requestLocationUpdates(
                    provider,
                    intervalMs,
                    0f,
                    locationListener,
                    looper
                )
                activeProviders.add(provider)
                Log.d(TAG, "requestLocationUpdates: provider=$provider intervalMs=$intervalMs")
            } catch (se: SecurityException) {
                Log.w(TAG, "requestLocationUpdates: permission rejected provider=$provider", se)
            } catch (t: Throwable) {
                Log.e(TAG, "requestLocationUpdates failed provider=$provider", t)
            }
        }
    }

    private fun stopLocationUpdates() {
        val mgr = locationManager ?: return
        if (activeProviders.isEmpty()) return
        try {
            mgr.removeUpdates(locationListener)
        } catch (t: Throwable) {
            Log.w(TAG, "removeUpdates", t)
        } finally {
            activeProviders.clear()
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

    private fun isStaleLocation(
        timestampMillis: Long,
        elapsedRealtimeNanos: Long?,
        nowMillis: Long
    ): Boolean {
        val staleThresholdMs = max(15_000L, intervalMs * 3L)
        if (timestampMillis < nowMillis - staleThresholdMs) {
            return true
        }
        if (timestampMillis > nowMillis + 5_000L) {
            return true
        }

        val lastTs = lastAcceptedTimestampMillis
        if (lastTs > 0L && timestampMillis + 1_000L < lastTs) {
            return true
        }

        val lastElapsed = lastAcceptedElapsedRealtimeNanos
        if (elapsedRealtimeNanos != null && lastElapsed != null && elapsedRealtimeNanos <= lastElapsed) {
            return true
        }

        return false
    }

    private fun shouldSuppressNetworkObservation(
        sourceProvider: GpsSourceProvider,
        nowMillis: Long,
        nowElapsedRealtimeNanos: Long?
    ): Boolean {
        if (sourceProvider != GpsSourceProvider.NETWORK) {
            return false
        }
        return hasRecentGpsFix(nowMillis, nowElapsedRealtimeNanos)
    }

    private fun hasRecentGpsFix(
        nowMillis: Long,
        nowElapsedRealtimeNanos: Long?
    ): Boolean {
        val windowMs = max(NETWORK_ACCEPT_IF_NO_GPS_MS_MIN, intervalMs + 5_000L)
        val lastGpsElapsed = lastAcceptedGpsElapsedRealtimeNanos
        if (nowElapsedRealtimeNanos != null && lastGpsElapsed != null) {
            val deltaMs = (nowElapsedRealtimeNanos - lastGpsElapsed) / 1_000_000L
            if (deltaMs in 0..windowMs) {
                return true
            }
        }

        val lastGpsTs = lastAcceptedGpsTimestampMillis
        if (lastGpsTs <= 0L) {
            return false
        }
        return (nowMillis - lastGpsTs) <= windowMs
    }

    private fun resetAcceptanceState() {
        lastAcceptedTimestampMillis = 0L
        lastAcceptedElapsedRealtimeNanos = null
        lastAcceptedGpsTimestampMillis = 0L
        lastAcceptedGpsElapsedRealtimeNanos = null
    }

    private fun classifySourceProvider(raw: String?): GpsSourceProvider {
        return when (raw) {
            LocationManager.GPS_PROVIDER -> GpsSourceProvider.GPS
            LocationManager.NETWORK_PROVIDER -> GpsSourceProvider.NETWORK
            else -> GpsSourceProvider.OTHER
        }
    }
}
