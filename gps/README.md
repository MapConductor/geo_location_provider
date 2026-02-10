# :gps (GPS Abstraction)

Gradle module: `:gps`

This module provides a small abstraction over platform location updates so the rest of the codebase can work with a stable domain model (`GpsObservation`) and a pluggable engine (`GpsLocationEngine`).

## Module boundary contract

What you implement or configure in this module determines how raw GPS observations are produced.

Inputs:

- Host app/service provides a `Context` and a `Looper` (for callback threading).
- Location permissions must be granted by the host app.

Outputs:

- A stream of `GpsObservation` values delivered to `GpsLocationEngine.Listener`.
  - Each observation includes lat/lon/accuracy and optional GNSS metadata (used/total satellites, CN0 mean).

## Public API (what other modules depend on)

- `GpsLocationEngine`: start/stop, update interval, set listener
- `GpsObservation`: one location observation plus optional GNSS metadata

Files:

- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/GpsLocationEngine.kt`
- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/GpsObservation.kt`

## Included implementation

- `LocationManagerGpsEngine`
  - Uses Android `LocationManager` and `GnssStatus.Callback`.
  - Intended for environments where Google Play Services are not available.
  - Exposes GNSS quality signals when available (used/total satellites, CN0 mean).

File:

- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/LocationManagerGpsEngine.kt`

## Typical usage

Create an engine, set a listener, then start:

```kotlin
val engine = LocationManagerGpsEngine(appContext, Looper.getMainLooper())
engine.setListener(object : GpsLocationEngine.Listener {
  override fun onGpsObservation(observation: GpsObservation) {
    // handle observation
  }
})
engine.updateInterval(5_000L)
engine.start()
```

Stop when no longer needed:

```kotlin
engine.stop()
engine.setListener(null)
```

## Permissions

Engines generally require location permissions. At minimum:

- `android.permission.ACCESS_FINE_LOCATION` (recommended)
- or `android.permission.ACCESS_COARSE_LOCATION`

If used from a foreground service, also declare the appropriate foreground service permissions in the app manifest.

## Implementing your own `GpsLocationEngine` (advanced)

If you want to use a different location provider (for example Google Play Services), implement `GpsLocationEngine` in this module.

What you write:

- A `GpsLocationEngine` implementation that translates platform callbacks into `GpsObservation` values.

What you get:

- `:core` can consume observations without depending on platform-specific callback types.
- Persistence and downstream logic do not change; only the observation source changes.

Skeleton:

```kotlin
class MyGpsEngine : GpsLocationEngine {
  private var listener: GpsLocationEngine.Listener? = null

  override fun setListener(listener: GpsLocationEngine.Listener?) {
    this.listener = listener
  }

  override fun updateInterval(intervalMs: Long) {
    // update platform request interval
  }

  override fun start() {
    // start receiving platform updates and call emit(...)
  }

  override fun stop() {
    // stop platform updates
  }

  private fun emit(observation: GpsObservation) {
    listener?.onGpsObservation(observation)
  }
}
```

Mapping tips:

- Use a monotonic time when possible (`elapsedRealtimeNanos`) to support de-duplication and ordering.
- GNSS metadata fields are optional; set them to `null` when your provider does not expose them.

## Notes

- The goal of this module is to keep GPS integration testable and replaceable.
- Do not leak Android callback types outside of this module; use `GpsObservation` instead.
