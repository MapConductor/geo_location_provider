# :core (Foreground Location Service)

Gradle module: `:core`

This module provides the foreground service (`GeoLocationService`). It is the "runtime orchestrator" of the system: it receives GPS observations, derives additional signals (heading, GNSS quality, optional correction), optionally runs Dead Reckoning (DR), and persists `LocationSample` rows via `:storageservice`.

## Key responsibilities

- Foreground tracking lifecycle (start/stop, notification channel).
- GPS observation handling via `:gps` (`GpsLocationEngine`).
- Optional GPS correction via the correction engine registry (`GpsCorrectionEngineRegistry`).
- Dead Reckoning integration (`DeadReckoningFactory`, DR prediction/backfill).
- Persistence via `StorageService` (no direct DAO usage outside `:storageservice`).

## Main entry point

- `GeoLocationService`: `core/src/main/java/com/mapconductor/plugin/provider/geolocation/service/GeoLocationService.kt`

## Module boundary contracts (inputs -> outputs)

### Inputs (what the host app must provide)

- Android component wiring:
  - declare the service in the app manifest
  - start/stop it from a foreground context (UI)
- Settings via `:storageservice`:
  - GPS interval (`SettingsRepository.intervalSecFlow`)
  - DR interval (`SettingsRepository.drIntervalSecFlow`)
  - DR mode (`SettingsRepository.drModeFlow`)
  - (Optional) DR GPS submit throttle (`SettingsRepository.drGpsIntervalSecFlow`)
- (Optional) GPS correction installation:
  - the host app can register a correction engine (for example `ImsEkf.install(...)`)

### Outputs (what this module produces)

- Inserts `LocationSample` rows via `StorageService.insertLocation(...)`.
  - `provider="gps"` for raw GPS observations.
  - `provider="gps_corrected"` when a correction engine returns a different observation.
  - `provider="dead_reckoning"` for DR points (when enabled).

### Effects while running

- Keeps a foreground notification active for location tracking.
- Starts/stops sensors as needed (heading sensor, optional correction sensors).
- Throttles duplicate inserts (simple signature-based guard).

## Recipes (what to do -> what you see)

### Recipe: Tracking only (GPS -> DB)

What you do:

- Start the service with `ACTION_START`.

What happens:

- Each incoming `GpsObservation` is converted into a `LocationSample` and inserted via `StorageService`.
- You will see rows with `provider="gps"` and timestamps from the observation.

### Recipe: Enable DR (GPS anchors -> DR samples)

What you do:

- Set `SettingsRepository.setDrIntervalSec(context, sec)` to a value `> 0`.

What happens:

- The service submits GPS fixes to the DR engine.
- Depending on DR mode:
  - Prediction: periodic DR points are inserted as `provider="dead_reckoning"`.
  - Completion: DR points are backfilled between GPS fixes and inserted as `provider="dead_reckoning"`.

### Recipe: Enable GPS correction (EKF -> corrected samples)

What you do:

- Install a correction engine (for example `ImsEkf.install(...)`) and enable it via its config.

What happens:

- For each GPS observation, the correction engine produces a fused observation.
- If it differs from the raw one, an additional `LocationSample` is inserted as `provider="gps_corrected"`.

### Recipe: Update sampling while running

What you do:

- Write new values into `SettingsRepository` (for example interval seconds) from UI.

What happens:

- The service observes these flows and updates GPS update interval and DR ticker behavior without requiring a restart.

### Service intents (public actions)

`GeoLocationService` supports simple intent actions for control:

- `ACTION_START` / `ACTION_STOP`
- `ACTION_UPDATE_INTERVAL` + `EXTRA_UPDATE_MS`
- `ACTION_UPDATE_DR_INTERVAL` + `EXTRA_DR_INTERVAL_SEC`
- `ACTION_UPDATE_DR_GPS_INTERVAL` + `EXTRA_DR_GPS_INTERVAL_SEC`

See the `companion object` in `GeoLocationService.kt` for the exact constant names.

## How to integrate in an app

1. Add the module dependency:

```kotlin
dependencies {
  implementation(project(":core"))
}
```

2. Declare the service in your app manifest and request the required permissions for your target SDK.
   - Location: `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
   - Foreground service: `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION` (Android 14+)
   - Notifications: `POST_NOTIFICATIONS` (Android 13+)

3. Start the service via `startForegroundService(...)` (or `ContextCompat.startForegroundService(...)`) using `ACTION_START`.

Example (start):

```kotlin
val intent = Intent(context, GeoLocationService::class.java).apply {
  action = GeoLocationService.ACTION_START
}
ContextCompat.startForegroundService(context, intent)
```

Example (stop):

```kotlin
val intent = Intent(context, GeoLocationService::class.java).apply {
  action = GeoLocationService.ACTION_STOP
}
context.startService(intent)
```

## GPS correction (optional)

This module defines an extension point for correcting raw GPS observations:

- `GpsCorrectionEngine` + `GpsCorrectionEngineRegistry`
- `ImsEkf` is a convenience installer that registers an IMU-aided EKF corrector when enabled by config.

When correction is enabled, corrected samples are persisted as a separate provider (`gps_corrected`) so they can be visualized and compared in the sample UI.

What you write:

- Install/register a correction engine (for example in `Application.onCreate()`):

```kotlin
ImsEkf.install(appContext)
```

Effect:

- On each GPS observation, the correction engine can output a "fused" observation.
- If the fused observation differs from the raw one, an additional `LocationSample` is inserted with `provider="gps_corrected"`.

## Dead reckoning (optional)

`GeoLocationService` can:

- submit GPS fixes to the DR engine
- emit realtime DR points (prediction mode)
- backfill DR points between GPS fixes (completion mode)

DR behavior is controlled by `SettingsRepository` flows (`drIntervalSecFlow`, `drModeFlow`) from `:storageservice`.

What you write:

- Set `drIntervalSec` to `0` to disable DR, or `> 0` to enable it.
- Choose `DrMode`:
  - `Prediction`: emit realtime DR samples on a ticker.
  - `Completion`: do not emit realtime points; instead backfill between GPS fixes.

Effect:

- DR points are inserted as `LocationSample(provider="dead_reckoning")`.
- The engine exposes `isLikelyStatic()`, and the service publishes it to the debug overlay state used by the sample UI.

## Detailed cross-module usage (host code -> runtime effects)

This section is intentionally concrete: it shows what you write in the host app and what changes you will observe in the system.

### Enable EKF correction end-to-end

What you write:

- Install the EKF corrector at process start.
- Enable it by persisting `ImsEkfConfig` via `ImsEkfConfigRepository`.

```kotlin
// Application.onCreate()
ImsEkf.install(appContext)
ImsEkfConfigRepository.set(
  appContext,
  ImsEkfConfig.defaults(ImsUseCase.WALK).copy(enabled = true)
)
```

What you get:

- `GeoLocationService` starts the correction engine lifecycle when the service runs.
- On each GPS observation, the corrector may output a fused observation.
- When the fused observation differs from raw, an additional `LocationSample(provider="gps_corrected")` is inserted.
- The sample UI can visualize this stream separately (shown as `GPS(EKF)` in the app).

Common pitfall:

- Installing EKF without setting `enabled=true` will not produce `gps_corrected` samples.

### Update sampling without restarting the service

What you write (from UI/ViewModel; these are `suspend` APIs):

```kotlin
viewModelScope.launch {
  SettingsRepository.setIntervalSec(context, sec = 1)
  SettingsRepository.setDrIntervalSec(context, sec = 0) // DR disabled
  SettingsRepository.setDrMode(context, mode = DrMode.Prediction)
}
```

What you get:

- The running service observes `SettingsRepository` flows and updates:
  - GPS update interval
  - DR ticker start/stop
  - DR mode (Prediction vs Completion)
- No service restart is required.

### Choose DR mode and understand the output

What you write:

- `drIntervalSec = 0` disables DR entirely.
- `drIntervalSec > 0` enables DR; `drMode` controls how samples appear.

What you get:

- `DrMode.Prediction`:
  - inserts additional realtime points roughly every `drIntervalSec` seconds as `provider="dead_reckoning"`
- `DrMode.Completion`:
  - does not emit a ticker stream
  - inserts backfilled points between GPS fixes as `provider="dead_reckoning"`

### Consume providers explicitly (GPS vs corrected vs DR)

What you write:

- Filter by `LocationSample.provider` when you want a specific stream for UI, export, or diagnostics.

```kotlin
StorageService.latestFlow(context, limit = 500).collect { rows ->
  val gps = rows.filter { it.provider == "gps" }
  val ekf = rows.filter { it.provider == "gps_corrected" }
  val dr = rows.filter { it.provider == "dead_reckoning" }
  // plot / export / compare as needed
}
```

What you get:

- A stable separation between raw GPS, corrected GPS, and DR samples.
- Easier A/B comparison in the Map screen and during export.

### Swap the GPS engine (advanced)

What you write:

- Implement a new `GpsLocationEngine` in `:gps` (for example, Google Play Services based).
- Update `GeoLocationService` to instantiate your engine instead of `LocationManagerGpsEngine`.

What you get:

- Core persistence and DR behavior remain unchanged.
- Only the source of `GpsObservation` values changes.

## Related modules

- `../gps/README.md`
- `../storageservice/README.md`
- `../deadreckoning/README.md`
