# GeoLocationProvider

GeoLocationProvider is an Android SDK and sample application for
**recording, storing, exporting, and uploading location data**.

It records background location to a Room database, exports it as
GeoJSON+ZIP (and optionally GPX), and can upload the results to **Google Drive** both via
scheduled nightly backup and optional realtime upload.

---

## Features

- Background location acquisition (configurable GPS / DR intervals).
- Storage in a Room database (`LocationSample`, `ExportedDay`).
- Export to GeoJSON or GPX format with optional ZIP compression.
- Automatic daily export at midnight (`MidnightExportWorker`).
- Manual export for “backup days before today” and “today preview”.
- Pickup feature (extract representative samples by period / count).
- Map visualization of GPS and Dead Reckoning tracks.
- Google Drive upload with folder selection and auth diagnostics.
- Realtime upload manager with interval and timezone settings.

---

## Architecture Overview

### Modules

The repository is a multi-module Gradle project:

- `:app` – Jetpack Compose sample app (history, Pickup, Map, Drive
  settings, Upload settings, manual backup).
- `:core` – Foreground service (`GeoLocationService`), device sensor
  handling, and shared config (`UploadEngine`). Persists via
  `:storageservice`, uses `:gps` for GNSS and `:deadreckoning` for
  IMU-based prediction.
- `:gps` – Thin GPS abstraction:
  - `GpsLocationEngine`, `GpsObservation`,
    `FusedLocationGpsEngine` (wraps `FusedLocationProviderClient`
    and `GnssStatus`).
- `:storageservice` – Room `AppDatabase` / DAOs and
  `StorageService`. Single entry point for location logs and
  export status.
- `:dataselector` – Pickup selection logic (grid snapping, thinning).
- `:datamanager` – GeoJSON export, ZIP compression,
  `MidnightExportWorker` / `MidnightExportScheduler`,
  `RealtimeUploadManager`, Drive HTTP client and uploaders, Drive and
  Upload prefs.
- `:deadreckoning` – Dead Reckoning engine and public API
  (`DeadReckoning`, `GpsFix`, `PredictedPoint`,
  `DeadReckoningConfig`, `DeadReckoningFactory`).
- `:auth-appauth` – AppAuth-based `GoogleDriveTokenProvider`.
- `:auth-credentialmanager` – Credential Manager + Identity-based
  `GoogleDriveTokenProvider`.

High-level dependency directions:

- `:app` → `:core`, `:dataselector`, `:datamanager`,
  `:storageservice`, `:deadreckoning`, `:gps`, auth modules.
- `:core` → `:gps`, `:storageservice`, `:deadreckoning`.
- `:dataselector` → `LocationSampleSource` abstraction only
  (implemented in `:app` using `StorageService`).
- `:datamanager` → `:core`, `:storageservice`, Drive integration.

### Key Components

**Entities and Storage**

- `LocationSample` – One row per recorded point (lat/lon, accuracy,
  provider, speed, heading/course, GNSS quality, battery state).
- `ExportedDay` – Per-day export state (local export, upload result,
  last error).
- `StorageService` – Single facade around Room:
  - Live tail via `latestFlow(ctx, limit)` (newest first).
  - Range queries via `getLocationsBetween(ctx, from, to, softLimit)`.
  - Convenience helpers for export status:
    `ensureExportedDay`, `oldestNotUploadedDay`,
    `nextNotUploadedDayAfter`, `exportedDayCount`,
    `markExportedLocal`, `markUploaded`, `markExportError`.
  - `lastSampleTimeMillis` is used to determine the last day that
    needs backup.

**GPS engine (`:gps`)**

- `FusedLocationGpsEngine`:
  - Uses `FusedLocationProviderClient` for positions and `GnssStatus`
    for signal quality.
  - Produces `GpsObservation` values with lat/lon, accuracy, speed,
    optional bearing, and GNSS `(used, total, cn0Mean)`.
- `GeoLocationService` only depends on `GpsLocationEngine` and
  processes `GpsObservation` into DB rows and DR fixes.

**Dead Reckoning (`:deadreckoning`)**

- Public API:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`.
- Implementation (`DeadReckoningImpl`) is internal:
  - Uses GPS fixes as anchors and integrates a 1D motion state along
    the latest GPS direction.
  - Maintains a short history of GPS “hold” positions to estimate
    effective speed from both GPS speed and inter-fix distance.
  - Uses a sliding window of horizontal accelerometer magnitude and
    speed to detect static vs moving.
  - Pins DR to the latest GPS hold location while static and enforces
    `maxStepSpeedMps` to drop physically impossible spikes.
  - `predict(from, to)` may return an empty list until a GPS anchor
    exists.
- `GeoLocationService`:
  - Maintains a filtered “GPS hold” position separate from the raw
    receive value.
  - Submits GPS fixes using the hold position to DR:
    `dr.submitGpsFix(GpsFix(timestampMillis, holdLat, holdLon, ...))`.
  - Drives a DR ticker:
    - Interval in seconds is read from
      `SettingsRepository.drIntervalSecFlow`.
    - `0` means “DR disabled (GPS only)” and the ticker is stopped.
    - `> 0` periodically calls `dr.predict(...)` and inserts
      `"dead_reckoning"` samples.
  - Updates `DrDebugState` with engine-side static flag so the map
    overlay can show `Static: YES/NO`.

**Export and Upload (`:datamanager`)**
  
  - Output formats: GeoJSON (via `GeoJsonExporter`) and GPX (via
    `GpxExporter`), selectable via `UploadOutputFormat` /
    `UploadSettingsScreen`.
  - Nightly export:
  - `MidnightExportWorker` / `MidnightExportScheduler`:
    - Timezone is taken from `UploadPrefs.zoneId` (IANA ID,
      defaults to `Asia/Tokyo`).
    - For each day up to “yesterday”, loads records via
      `StorageService.getLocationsBetween`, exports GeoJSON+ZIP to
      Downloads via `GeoJsonExporter`, and marks local export via
      `markExportedLocal`.
    - Resolves effective Drive folder using `DrivePrefsRepository`
      (UI folder id/resourceKey) and `AppPrefs.folderId` as fallback.
    - Uses `UploaderFactory` with `UploadEngine.KOTLIN` for upload.
    - Records per-day status in `ExportedDay` and a human-readable
      summary in `DrivePrefsRepository.backupStatus`.
    - Always deletes the ZIP file; only deletes Room rows when upload
      succeeds and the day had records.
- Manual backlog:
  - “Backup days before today” triggers `MidnightExportWorker.runNow`
    in a mode that scans from the first to the last sample day using
    `StorageService.lastSampleTimeMillis`, so days can be
    re-processed on demand.
- Realtime upload:
  - `RealtimeUploadManager`:
    - Subscribes to `UploadPrefsRepository` (schedule, interval,
      timezone) and `SettingsRepository` (GPS/DR intervals).
    - Watches `StorageService.latestFlow(limit = 1)` for new samples.
    - Only acts when `UploadSchedule.REALTIME` is selected and Drive
      is configured.
    - Applies a cooldown based on `intervalSec` (0 or equal to the
      sampling interval behave as “every sample”).
    - On upload:
      - Loads all samples via `StorageService.getAllLocations`.
      - Generates GeoJSON to a cache file based on the latest sample
        time and configured timezone.
      - Resolves effective Drive folder, creates an uploader via
        `UploaderFactory.create(context, appPrefs.engine)`, and
        uploads the JSON.
      - Deletes the cache file and, on success, deletes the uploaded
        samples from Room.

**UI (Compose, `:app`)**

- `MainActivity`:
  - Activity-level `NavHost` with routes: `"home"`,
    `"drive_settings"`, `"upload_settings"`.
  - Requests runtime permissions via
    `ActivityResultContracts.RequestMultiplePermissions` and starts
    `GeoLocationService` when granted.
- `AppRoot`:
  - Nested `NavHost` with `"home"`, `"pickup"`, `"map"`.
  - AppBar:
    - Title reflects current route (“GeoLocation”, “Pickup”, “Map”).
    - Back button on Pickup and Map.
    - On home route: `Map`, `Pickup`, `Drive`, `Upload` buttons plus
      `ServiceToggleAction` (start/stop service).
- Screens:
  - `GeoLocationProviderScreen` – Interval controls + history list.
  - `PickupScreen` – Pickup conditions and results (via
    `SelectorUseCases`).
  - `MapScreen` – MapConductor `GoogleMapsView` that:
    - Shows GPS and DR polylines (blue GPS behind, red DR in front)
      built strictly in time order.
    - Has `GPS` / `DeadReckoning` checkboxes, `Count` field
      (1–5000), and `Apply` / `Cancel` buttons.
    - On `Apply`, locks controls and draws polylines using up to
      `Count` newest samples.
    - On `Cancel`, clears polylines and unlocks controls while
      keeping camera position.
    - Draws an accuracy circle for the latest GPS sample.
    - Shows a debug overlay with:
      - `GPS`, `DR`, `ALL` counts (`shown / DB total`).
      - Static flag from `DrDebugState`.
      - DR–GPS distance and latest GPS accuracy / “weight”.
  - `DriveSettingsScreen` – Auth method selection (Credential Manager
    vs AppAuth), sign-in/out, “Get token”, Drive folder settings,
    “Backup days before today”, and “Today preview”.
  - `UploadSettingsScreen` – Upload on/off, nightly vs realtime,
    interval (seconds), timezone, and status hints.

---

## Public Library Surface

### History list behavior

The sample app's history view does not render directly from a live
Room query. Instead, `HistoryViewModel` keeps an in-memory buffer of
up to 9 latest `LocationSample` rows, built from
`StorageService.latestFlow(limit = 9)` and sorted by `timeMillis`
(newest first). This buffer is decoupled from realtime uploads, so
rows deleted from Room after a successful upload remain visible in the
history list until they naturally fall out of the buffer.

 The primary reusable APIs are intentionally small. When consuming the
libraries, prefer these modules and types:

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`,
    `SettingsRepository`.
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`,
    `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`.
- `:datamanager`:
  - Auth and tokens:
    `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`.
  - Drive / upload settings:
    `DrivePrefsRepository`, `UploadPrefsRepository`,
    `UploadSchedule`.
  - Drive API:
    `DriveApiClient`, `DriveFolderId`, `UploadResult`.
  - Export / upload:
    `UploadEngine`, `GeoJsonExporter`, `Uploader`, `UploaderFactory`,
    `RealtimeUploadManager`.
  - Background jobs:
    `MidnightExportWorker`, `MidnightExportScheduler`.
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`.
- `:gps`:
  - `GpsLocationEngine`, `GpsObservation`,
    `FusedLocationGpsEngine`.

Other types (DAOs, internal engines, HTTP helpers, etc.) should be
treated as implementation details and may change without notice.

---

## Minimal Integration Examples

### 1. Start the location service and observe history

```kotlin
// In your Activity
private fun startLocationService() {
    val intent = Intent(this, GeoLocationService::class.java)
        .setAction(GeoLocationService.ACTION_START)
    ContextCompat.startForegroundService(this, intent)
}

// Observe the latest N samples from your UI/ViewModel
val flow: Flow<List<LocationSample>> =
    StorageService.latestFlow(context, limit = 100)
```

### 2. Configure Drive upload, nightly backup, and realtime upload

```kotlin
// In your Application class
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Example: reuse Credential Manager provider from :auth-credentialmanager
        val provider = CredentialManagerTokenProvider(
            context = this,
            serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
        )

        // Register background provider for workers and realtime uploads
        DriveTokenProviderRegistry.registerBackgroundProvider(provider)

        // Schedule daily export at midnight (uses UploadPrefs timezone)
        MidnightExportScheduler.scheduleNext(this)

        // Start realtime upload manager (reads UploadPrefs schedule/interval)
        RealtimeUploadManager.start(this)
    }
}
```

```kotlin
// Optional: manually upload a file to Drive using UploaderFactory
suspend fun uploadFileNow(
    context: Context,
    tokenProvider: GoogleDriveTokenProvider,
    fileUri: Uri,
    folderId: String
): UploadResult {
    val uploader = UploaderFactory.create(
        context = context.applicationContext,
        engine = UploadEngine.KOTLIN,
        tokenProvider = tokenProvider
    ) ?: error("Upload engine not available")

    return uploader.upload(fileUri, folderId, fileName = null)
}
```

---

## Drive Authentication Options (Overview)

All Drive uploads go through `GoogleDriveTokenProvider`. Recommended
paths:

- **Credential Manager** (`:auth-credentialmanager`):
  - Uses Google Identity / Credential Manager with a web/server
    client ID (`CREDENTIAL_MANAGER_SERVER_CLIENT_ID`).
  - Good default for background workers and the sample app.
- **AppAuth** (`:auth-appauth`):
  - Uses AppAuth for Android with an “installed app” client ID
    (`APPAUTH_CLIENT_ID`) and a custom scheme redirect
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect`.

Both implementations:

- Return bare access tokens (no `"Bearer "` prefix).
- Return `null` on normal failures instead of throwing.
- Must not start UI from background contexts; workers use
  `DriveTokenProviderRegistry` instead.
- The sample `App` wires `DriveTokenProviderRegistry` so that
  background uploads follow the auth method selected in
  `DriveSettingsScreen`:
  - It registers a Credential Manager provider by default at startup.
  - It then reads `DrivePrefs.authMethod` and swaps the background
    provider to AppAuth when AppAuth is selected and already
    authenticated.
  - `DriveSettingsViewModel` keeps both `authMethod` and
    `accountEmail` in sync and updates the registry when the user
    completes Credential Manager or AppAuth sign-in.

See `AGENTS.md`, `README_JA.md`, or `README_ES.md` for more detailed
Cloud Console configuration.

---

## Configuration Files

- `local.properties` (not committed):
  - Local Android SDK path and Gradle-related settings.
- `secrets.properties` (not committed):
  - Values used by the `secrets-gradle-plugin`, for example:

```properties
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

Use `local.default.properties` as a template and adjust values for
your environment.

---

## Development Notes

- Kotlin + Jetpack Compose UI.
- Persistence via Room (`:storageservice`) and DataStore for settings.
- Background jobs via WorkManager (`MidnightExportWorker`,
  `MidnightExportScheduler`).
- GPS / Dead Reckoning via dedicated modules (`:gps`,
  `:deadreckoning`).

Source encoding and comments:

- All production source files (Kotlin / Java / XML / Gradle scripts)
  use **ASCII only** to avoid encoding issues.
- Multilingual documentation is provided in separate `*.md` files.
- Public APIs use KDoc (`/** ... */`); internal details use simple
  `// ...` comments with consistent section headings.

---

## Feature Status

| Feature                      | Status          | Notes                                              |
|------------------------------|-----------------|----------------------------------------------------|
| Location recording (Room)    | [v] Implemented | Saved as `LocationSample` rows                     |
| Daily export (GeoJSON/GPX+ZIP) | [v] Implemented | `MidnightExportWorker` + `MidnightExportScheduler` |
| Google Drive upload          | [v] Implemented | Kotlin-based uploader via `UploaderFactory`        |
| Pickup (interval/count)      | [v] Implemented | `:dataselector` + Compose UI                       |
| Map visualization            | [v] Implemented | GPS/DR polylines + debug overlay                   |
| Realtime upload              | [v] Implemented | `RealtimeUploadManager` + Upload settings          |
| UI: DriveSettingsScreen      | [v] Implemented | Auth, folder settings, backup / preview actions    |
| UI: UploadSettingsScreen     | [v] Implemented | Schedule, interval, timezone, guardrails           |
| UI: PickupScreen             | [v] Implemented | Pickup input and result list                       |
| UI: History list             | [v] Implemented | Chronological view of saved samples                |
