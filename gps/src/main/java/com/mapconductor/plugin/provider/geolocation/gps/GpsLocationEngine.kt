package com.mapconductor.plugin.provider.geolocation.gps

/**
 * Abstraction over platform-specific GPS location updates.
 *
 * Implementations provide a stream of [GpsObservation]s and allow
 * callers to control sampling intervals.
 */
interface GpsLocationEngine {

    interface Listener {
        fun onGpsObservation(observation: GpsObservation)
    }

    fun start()

    fun stop()

    fun updateInterval(intervalMs: Long)

    fun setListener(listener: Listener?)
}

