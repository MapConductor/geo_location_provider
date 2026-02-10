# :deadreckoning (IMU Dead Reckoning)

Gradle module: `:deadreckoning`

This module provides an IMU-based dead reckoning engine that can predict intermediate positions between GPS fixes. The public API is intentionally small and is accessed via `DeadReckoningFactory`.

## Module boundary contract (inputs -> outputs)

Inputs:

- IMU sensors (accelerometer/gyro) must exist on the device.
- Periodic GPS fixes provided by the host (`submitGpsFix`).
- A time range to predict (`predict(fromMillis, toMillis)`).

Outputs:

- `PredictedPoint` values for the requested time range.
- A static/moving heuristic (`isLikelyStatic()`).

## Public API

Package: `com.mapconductor.plugin.provider.geolocation.deadreckoning.api`

- `DeadReckoning`: start/stop, `submitGpsFix(...)`, `predict(fromMillis, toMillis)`
- `DeadReckoningConfig`: tuning parameters
- `GpsFix`: input anchor from GPS
- `PredictedPoint`: output points
- `DeadReckoningFactory`: creates a `DeadReckoning` instance

Files:

- `deadreckoning/src/main/java/com/mapconductor/plugin/provider/deadreckoning/api/DeadReckoning.kt`
- `deadreckoning/src/main/java/com/mapconductor/plugin/provider/deadreckoning/api/DeadReckoningConfig.kt`
- `deadreckoning/src/main/java/com/mapconductor/plugin/provider/deadreckoning/api/DeadReckoningFactory.kt`

## Typical usage

```kotlin
val dr = DeadReckoningFactory.create(appContext, DeadReckoningConfig())
dr.start()

dr.submitGpsFix(
  GpsFix(
    timestampMillis = t,
    lat = lat,
    lon = lon,
    accuracyM = accuracy,
    speedMps = speed
  )
)

val points: List<PredictedPoint> = dr.predict(fromMillis = t0, toMillis = t1)
```

Stop when done:

```kotlin
dr.stop()
```

## Cross-module usage patterns (what you write -> what you get)

### Pattern: Realtime prediction ticker (DR Prediction mode)

What you write:

- Submit GPS fixes as anchors.
- Periodically call `predict(lastFixMillis, nowMillis)` on a timer.

What you get:

- A list of intermediate `PredictedPoint` values that can be persisted/visualized as a smoother path.
- When used from `:core`, this corresponds to inserting additional `LocationSample(provider="dead_reckoning")` points on a ticker.

### Pattern: Backfill between GPS fixes (DR Completion mode)

What you write:

- On each new GPS fix, call `predict(previousFixMillis, currentFixMillis)` once.

What you get:

- A dense bridge of points between two GPS anchors without generating extra realtime ticker points.
- When used from `:core`, this corresponds to backfilling `dead_reckoning` samples between GPS observations.

### Pattern: Clamp behavior when static

What you write:

- Query `dr.isLikelyStatic()` and use it to clamp output (for example stop moving the marker, or reduce updates).

What you get:

- A simple, engine-provided hint to reduce jitter while the device is likely not moving.

### Pattern: Tune with `DeadReckoningConfig`

What you write:

- Start with defaults, then adjust only a small set of parameters:
  - `velocityGain`: how strongly GPS speed influences the internal estimate
  - `maxStepSpeedMps`: cap IMU-derived step speeds to avoid spikes
  - `staticAccelVarThreshold` / `staticGyroVarThreshold`: static detection sensitivity

What you get:

- A stable output that matches your motion profile (walk/bike/car) and sensor quality.

## Notes

- Predictions may be empty until the first GPS fix is submitted (no absolute anchor yet).
- `isLikelyStatic()` exposes the engine's internal static/moving heuristic; callers can use it to clamp behavior.

## Related modules

- `../core/README.md` (service integration and modes)
