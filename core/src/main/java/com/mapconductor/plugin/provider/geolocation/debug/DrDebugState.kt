package com.mapconductor.plugin.provider.geolocation.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Simple debug holder for DeadReckoning state.
 *
 * - Updated from GeoLocationService whenever DR ticker runs.
 * - Read from the app side (for example MapViewModel) to display
 *   internal DR flags such as isLikelyStatic() on the debug overlay.
 *
 * This type is kept internal to the library and is intended only
 * for diagnostics in the sample app.
 */
data class DrDebugSnapshot(
    val isStatic: Boolean,
    val lastUpdateMillis: Long
)

object DrDebugState {

    private val _snapshot =
        MutableStateFlow(DrDebugSnapshot(isStatic = false, lastUpdateMillis = 0L))

    val snapshot: StateFlow<DrDebugSnapshot> = _snapshot

    fun update(isStatic: Boolean, lastUpdateMillis: Long) {
        _snapshot.value = DrDebugSnapshot(isStatic, lastUpdateMillis)
    }
}

