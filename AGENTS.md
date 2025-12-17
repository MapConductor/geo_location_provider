# Repository Guidelines

This document summarizes the common policies, coding conventions, and
responsibility boundaries between modules when working on the
GeoLocationProvider repository.

Read it once before changing code and follow these guidelines when
implementing changes.

---

## Project Structure and Modules

- Root Gradle project `GeoLocationProvider` consists of:
  - `:app`  EJetpack Compose sample app (history, Pickup, Map, Drive
    settings, Upload settings, manual backup).
  - `:core`  EForeground location service (`GeoLocationService`),
    device sensor handling, and shared config such as `UploadEngine`.
    Persists via `:storageservice`, uses `:gps` for GNSS and
    `:deadreckoning` for IMU prediction.
  - `:gps`  EGPS abstraction (`GpsLocationEngine`,
    `GpsObservation`, `FusedLocationGpsEngine`) which wraps
    `FusedLocationProviderClient` and `GnssStatus`.
  - `:storageservice`  ERoom `AppDatabase`, DAOs, and the
    `StorageService` facade. Owns location logs and export status.
  - `:dataselector`  EPickup selection logic based on
    `LocationSampleSource` and `SelectorCondition`.
  - `:datamanager`  EGeoJSON / GPX export, ZIP compression,
    `MidnightExportWorker` / `MidnightExportScheduler`,
    `RealtimeUploadManager`, Drive HTTP client and uploaders, Drive
    and Upload preference repositories.
  - `:deadreckoning`  EDead Reckoning engine and public API
    (`DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`).
  - `:auth-appauth`  EAppAuth-based `GoogleDriveTokenProvider`.
  - `:auth-credentialmanager`  ECredential Manager + Identity-based
    `GoogleDriveTokenProvider`.
  - `mapconductor-core-src`  EVendored MapConductor core sources used
    only from `:app` (treat as read-only; changes should go upstream).

- High-level dependency directions:
  - `:app` ↁE`:core`, `:dataselector`, `:datamanager`,
    `:storageservice`, `:deadreckoning`, `:gps`, auth modules,
    MapConductor.
  - `:core` ↁE`:gps`, `:storageservice`, `:deadreckoning`.
  - `:datamanager` ↁE`:core`, `:storageservice`, Drive integration.
  - `:dataselector` ↁE`LocationSampleSource` abstraction only
    (implementation lives in `:app`, wrapping `StorageService`).

- Production code lives under each module’s `src/main/java`,
  `src/main/kotlin`, and `src/main/res`.
  Build outputs go under each module’s `build/` directory.

- Machine-specific settings (Android SDK path, etc.) live in the root
  `local.properties` (not committed).

- Secrets and auth-related configuration are stored in
  `secrets.properties` (not committed).
  Use `local.default.properties` as the template.

---

## Build, Test, and Development Commands

- Typical Gradle commands (run from project root):
  - `./gradlew :app:assembleDebug`  EBuild sample app (debug).
  - `./gradlew :core:assemble` / `:storageservice:assemble`  E    Build library modules.
  - `./gradlew :deadreckoning:assemble` / `:gps:assemble`  E    Build sensor/DR modules.
  - `./gradlew lint`  EAndroid / Kotlin static analysis.
  - `./gradlew test` / `:app:connectedAndroidTest`  EUnit / UI tests.

Use Android Studio for day-to-day development; use command line
mainly for CI and verification.

---

## Coding Style and Naming

- Main technologies: Kotlin, Jetpack Compose, Gradle Kotlin DSL.
  - Indent with 4 spaces.
  - Assume UTF-8 for all source files.

- Package guidelines:
  - App/service: `com.mapconductor.plugin.provider.geolocation.*`
  - GPS abstraction: `com.mapconductor.plugin.provider.geolocation.gps.*`
  - Storage: `com.mapconductor.plugin.provider.storageservice.*`
  - Dead Reckoning:
    `com.mapconductor.plugin.provider.geolocation.deadreckoning.*`

- Naming:
  - Classes / objects / interfaces  EPascalCase
  - Functions / variables / properties  EcamelCase
  - Constants  EUPPER_SNAKE_CASE
  - Screen-level Composables  Eend with `Screen`
  - ViewModels  Eend with `ViewModel`
  - Workers / schedulers  Eend with `Worker` / `Scheduler`

- Functions should have a single, focused responsibility and clear
  names.

- Remove unused imports and dead code when you find them.

### Comment and Encoding Policy

- All production source files (Kotlin / Java / XML / Gradle scripts,
  etc.) must use **ASCII characters only**.
  - Do not use multibyte characters in code, comments, or string
    literals.
- Multilingual text (Japanese / Spanish) is allowed only in
  documentation (`*.md`) such as `README_JA.md`,
  `README_ES.md`.
- Prefer KDoc (`/** ... */`) for public APIs and key classes; use
  `// ...` line comments for internal notes.
- Section headers inside code should be simple
  (for example `// ---- Section ----`) and consistent across modules.

---

## Layering and Responsibilities

### StorageService / Room Access

- Access to Room `AppDatabase` / DAOs from outside `:storageservice`
  must go through `StorageService`.
  - Only `:storageservice` may touch DAO types directly.
  - Do not import DAO types from `:app`, `:core`, `:datamanager`, or
    `:dataselector`.

- Main `StorageService` API:
  - `latestFlow(ctx, limit)`  EFlow of latest `LocationSample`s,
    newest first (used by history, Map, realtime upload).
  - `getAllLocations(ctx)`  EAll locations, ascending by
    `timeMillis`. Use only for small datasets (previews, debugging,
    realtime snapshot).
  - `getLocationsBetween(ctx, from, to, softLimit)`  ELocations in
    `[from, to)` (half-open), ascending by `timeMillis`. Use for
    daily export and range-based Pickup.
  - `insertLocation(ctx, sample)`  EInserts one sample and logs
    before/after counts with `DB/TRACE`.
  - `deleteLocations(ctx, items)`  EBatch delete; no-op for an empty
    list; propagates exceptions.
  - `lastSampleTimeMillis(ctx)`  EMaximum `timeMillis` across all
    samples, or `null` when empty (used by export backlog logic).
  - `ensureExportedDay(ctx, epochDay)`  EEnsure an `ExportedDay`
    row exists.
  - `oldestNotUploadedDay(ctx)`  EOldest day not yet marked as
    uploaded.
  - `exportedDayCount(ctx)`  ETotal `ExportedDay` row count (for
    diagnostics / UI).
  - `nextNotUploadedDayAfter(ctx, afterEpochDay)`  ENext
    non-uploaded day strictly after `afterEpochDay`.
  - `markExportedLocal(ctx, epochDay)` /
    `markUploaded` / `markExportError`  EUpdate per-day export/upload
    status.

- All DB access runs on `Dispatchers.IO` inside `StorageService`.
  Callers must avoid additional blocking on the main thread.

### dataselector / Pickup

- `:dataselector` depends only on `LocationSampleSource` and must not
  know about Room or `StorageService`.
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)`
    uses half-open `[from, to)` semantics.

- `SelectorRepository`:
  - When `intervalSec == null`: direct extraction (no grid).
    - Fetch ascending by time, apply optional accuracy filter, apply
      limit, then order according to `SortOrder`.
  - When `intervalSec != null`: grid snapping.
    - `T = intervalSec * 1000L`.
    - Generates grid targets from start/end depending on
      `SortOrder`.
    - For each grid window `±T/2`, chooses one representative
      sample (earlier sample preferred when multiple exist).
    - Gaps are represented as `SelectedSlot(sample = null)`.

- UI (`:app`) uses dataselector via `SelectorUseCases` and does not
  touch Room or DAOs directly.

### GeoLocationService / GPS / Dead Reckoning

- `GeoLocationService` (`:core`) is a foreground service that
  orchestrates:
  - GPS via `GpsLocationEngine` and `FusedLocationGpsEngine`
    (`:gps`).
  - IMU-based prediction via `DeadReckoning`
    (`:deadreckoning`).
  - Battery and heading via helper utilities.

- GPS:
  - `FusedLocationGpsEngine` converts `FusedLocationProviderClient`
    and `GnssStatus` callbacks into `GpsObservation` values with
    lat/lon, accuracy, speed, optional bearing, and GNSS quality
    (used/total satellites and CN0 mean).
  - Each observation is transformed into a `LocationSample` with
    provider `"gps"`. Duplicate suppression is based on a compact
    signature so repeated callbacks do not flood the DB.
  - A “GPS hold Eposition is maintained separately from the raw
    receive position and blends old/new points using speed and
    accuracy to reduce jitter while keeping high-speed motion
    responsive.

- Dead Reckoning:
  - A `DeadReckoning` instance is created via
    `DeadReckoningFactory.create(applicationContext, config)` using a
    tuned `DeadReckoningConfig` (static detection thresholds, window
    size, `velocityGain`, etc.).
  - GPS fixes are submitted using the **hold** position:
    `dr.submitGpsFix(GpsFix(timestampMillis, holdLat, holdLon, ...))`.
  - DR prediction is driven by a ticker:
    - Interval in seconds is `drIntervalSec`.
    - `drIntervalSec == 0` means “Dead Reckoning disabled (GPS
      only) Eand the ticker is stopped.
    - `drIntervalSec > 0` starts a loop which:
      - Calls `dr.predict(fromMillis = lastFixMillis, toMillis =
        now)`.
      - Uses the last `PredictedPoint` to insert a
        `"dead_reckoning"` `LocationSample` (with duplicates
        suppressed).
      - Reads `dr.isLikelyStatic()` and publishes it to
        `DrDebugState` so the Map overlay can reflect engine-side
        static detection.
  - `DeadReckoningImpl` is internal:
    - Uses GPS fixes to anchor a 1D state along the latest GPS
      direction.
    - Uses a slide window of accelerometer magnitude and GPS/hold
      speed to decide static vs moving.
    - Pins DR to the latest GPS hold location while static, and
      enforces `maxStepSpeedMps` to avoid physically impossible
      spikes.
    - `predict` may return an empty list before any GPS anchor is
      available.

### Settings Handling (SettingsRepository)

- `storageservice.prefs.SettingsRepository` encapsulates sampling
  intervals in DataStore:
  - `intervalSecFlow(context)`  EFlow of GPS interval in seconds
    (defaults and minimums applied).
  - `drIntervalSecFlow(context)`  EFlow of DR interval in seconds.
    Contract:
    - `0` ↁE“DR disabled (GPS only) E
    - `> 0` ↁEclamped to at least 1 second.
  - `currentIntervalMs(context)` /
    `currentDrIntervalSec(context)`  Esynchronous getters for legacy
    code; prefer Flow APIs for new code.

- `GeoLocationService` subscribes to both flows:
  - GPS interval changes restart GPS updates when the service is
    running.
  - DR interval changes start/stop the DR ticker via
    `applyDrInterval`.

- `IntervalSettingsViewModel`:
  - Provides text fields for GPS and DR intervals and a “Save &
    Apply Ebutton.
  - DR interval is validated against `[0, floor(GPS / 2)]` seconds.
  - When DR interval is set to `0`, a toast explicitly notes
    “DR disabled (GPS only) E

---

## Auth and Drive Integration

### GoogleDriveTokenProvider and Implementations

- `GoogleDriveTokenProvider` is the main abstraction for Drive access
  tokens.
  - Implementations must return **bare access tokens** (without
    `"Bearer "` prefix).
  - Normal failures (network error, not signed in, missing consent)
    are reported by returning `null`, not by throwing.
  - Providers must never launch UI from background contexts; if a UI
    flow is needed, they return `null` and let the app decide.

- Recommended implementations:
  - `CredentialManagerTokenProvider` (`:auth-credentialmanager`).
  - `AppAuthTokenProvider` (`:auth-appauth`).
  - `GoogleAuthRepository` is legacy (GoogleAuthUtil-based) and
    should not be used for new code.

### Credential Manager / AppAuth wrappers

- `CredentialManagerAuth` wraps `CredentialManagerTokenProvider` and
  reads `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` from `BuildConfig`.
  - Treats it as the server (web) client ID.

- `AppAuthAuth` wraps `AppAuthTokenProvider`:
  - `clientId = BuildConfig.APPAUTH_CLIENT_ID`.
  - Redirect URI:
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect`.
  - Requires an “installed app Eclient in Google Cloud Console with
    the above scheme/redirect.

- `AppAuthSignInActivity`:
  - Transparent Activity that starts the AppAuth sign-in flow and
    receives the redirect.
  - Must be exported with an intent filter for the custom scheme and
    path `/oauth2redirect`.

### DriveTokenProviderRegistry / Background

  - `DriveTokenProviderRegistry` provides a process-wide background
    `GoogleDriveTokenProvider` used from:
    - `MidnightExportWorker` / `MidnightExportScheduler`.
    - `RealtimeUploadManager` (when `UploaderFactory.create` is
      called without an explicit provider).

- `App.onCreate()` is responsible for wiring the background provider
  and schedulers:
    - The sample `App` first registers
      `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))`
      as a default.
    - It then reads `DrivePrefs.authMethod` to swap the provider to
      `AppAuthAuth.get(this)` when AppAuth is selected and already
      authenticated.
    - It always calls `MidnightExportScheduler.scheduleNext(this)`
      and `RealtimeUploadManager.start(this)` so that both nightly and
      realtime uploads are active according to `UploadPrefs`.

- Background workers must:
  - Never start UI.
  - Treat `getAccessToken() == null` as “not authorized E
  - Always delete temporary ZIP/JSON files after each attempt.

### Drive / Upload settings persistence

- Two layers for Drive settings plus one for upload behavior:
  - `core.prefs.AppPrefs`  Elegacy SharedPreferences:
    - `engine` (`UploadEngine`), `folderId`, and a few other fields.
    - Used by older paths and as a fallback for workers.
  - `datamanager.prefs.DrivePrefsRepository`  EDataStore-based Drive
    prefs:
    - `folderId`, `folderResourceKey`, `accountEmail`,
      `uploadEngineName`, `authMethod`, `tokenUpdatedAtMillis`,
      `backupStatus`.
  - `datamanager.prefs.UploadPrefsRepository`  EDataStore-based upload
    prefs:
    - `schedule` (`UploadSchedule.NONE` / `NIGHTLY` /
      `REALTIME`).
    - `intervalSec` (0 or 1 E6400 seconds).
    - `zoneId` (IANA timezone string, defaults to `Asia/Tokyo`).

- UI should read/write via the repositories; workers may use both the
  repositories and `AppPrefs` snapshots for backward compatibility.

---

## Export and Upload

### MidnightExportWorker / MidnightExportScheduler
  
- `MidnightExportWorker` processes “backlog up to yesterday E
    - Timezone is read from `UploadPrefs.zoneId` (defaults to
      `Asia/Tokyo`).
    - One-day ranges use `[0:00, 24:00)` in that zone.
    - Uses `StorageService.ensureExportedDay`,
      `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,
      `exportedDayCount`, and `lastSampleTimeMillis` to decide which
      days to process and to populate status strings.
    - For each day:
      - Loads records via `StorageService.getLocationsBetween`.
      - Exports a ZIP to Downloads via `GeoJsonExporter` (GeoJSON) or
        `GpxExporter` (GPX) according to `UploadOutputFormat`.
      - Marks local export via `markExportedLocal`.
      - Resolves effective Drive folder (DrivePrefs first, then
        AppPrefs).
      - Creates an uploader via `UploaderFactory` using
        `UploadEngine.KOTLIN` when configured.
      - Uploads the ZIP and records success/failure via
        `markUploaded` / `markExportError`.
      - Writes a human-readable `backupStatus` summary string via
        `DrivePrefsRepository` for Drive settings UI.
      - Always deletes the ZIP from Downloads to avoid filling storage.
      - Deletes that day’s `LocationSample` rows from Room only when
        upload succeeds and the day had records.

- Manual backlog:
  - `MidnightExportWorker.runNow(context)` enqueues an immediate
    worker with a flag to force a full range scan using earliest and
    latest sample dates, so “Backup days before today Ere-processes
    all applicable days even when `exported_days.uploaded` is already
    true.

### RealtimeUploadManager / Upload settings

- `RealtimeUploadManager` watches new `LocationSample` rows and
  uploads them as GeoJSON or GPX according to upload settings:
  - Observes `UploadPrefsRepository.scheduleFlow`, `intervalSecFlow`,
    `zoneIdFlow`, and `outputFormatFlow`.
  - Observes GPS/DR sampling intervals via
    `SettingsRepository.intervalSecFlow` and
    `SettingsRepository.drIntervalSecFlow`.
  - Subscribes to `StorageService.latestFlow(limit = 1)` and only
    reacts when:
    - `UploadSchedule.REALTIME` is selected, and
    - Drive is configured (engine/folder ok).
  - Applies a cooldown based on `intervalSec`:
    - `intervalSec == 0` or equal to the active sampling interval
      ↁEtreat as “every sample E
  - When an upload is due:
    - Loads all samples via `StorageService.getAllLocations`.
    - Builds either GeoJSON (`YYYYMMDD_HHmmss.json`) or GPX
      (`YYYYMMDD_HHmmss.gpx`) into a cache file based on the latest
      sample, configured timezone, and `UploadOutputFormat`.
    - Resolves effective Drive folder (DrivePrefs UI folder first,
      then AppPrefs).
    - Creates an uploader via `UploaderFactory.create(context,
      appPrefs.engine)`; this uses `DriveTokenProviderRegistry` for
      auth when possible.
    - Uploads the generated file, then deletes the cache file.
    - On success, deletes the uploaded `LocationSample` rows via
      `StorageService.deleteLocations`.

- `UploadSettingsScreen` / `UploadSettingsViewModel`:
  - Control:
    - Whether upload is enabled (`UploadSchedule != NONE` and Drive
      configured).
    - Schedule: nightly vs realtime.
    - Interval (seconds) for realtime; clamped to [0, 86400].
    - Timezone (IANA ID) shared with nightly export and Today
      preview.
  - Show warnings when upload is enabled without Drive sign-in.

---

## UI / Compose (`:app`)

- Patterns:
  - Top-level `MainActivity` hosts an Activity-level `NavHost` with
    routes `"home"`, `"drive_settings"`, `"upload_settings"`.
  - `AppRoot` hosts an inner `NavHost` with `"home"`, `"pickup"`,
    `"map"`.
  - State is exposed via `StateFlow` / `uiState` in ViewModels and
    consumed by Composables.

- Screens:
  - `GeoLocationProviderScreen`:
    - Interval controls (`IntervalSettingsViewModel`) plus history
      list (`HistoryViewModel`).
    - `HistoryViewModel` maintains an in-memory buffer of up to
      9 latest `LocationSample` rows, populated from
      `StorageService.latestFlow(limit = 9)` and sorted by
      `timeMillis` (newest first). The buffer is not cleared when
      rows are deleted from Room (for example after uploads); items
      are dropped only when the buffer exceeds its limit.
  - `PickupScreen`:
    - Pickup conditions and results, backed by `SelectorUseCases`.
  - `MapScreen` / `GoogleMapsExample`:
    - Uses MapConductor `GoogleMapsView`.
    - Top row: `GPS` and `DeadReckoning` checkboxes, `Count` field
      (1 E000), `Apply` / `Cancel` button.
    - On `Apply`, controls are locked and up to `Count` newest samples
      are considered:
      - GPS polyline: blue, thicker, drawn first (behind).
      - DR polyline: red, thinner, drawn second (in front).
      - Both connect samples **in time order** by `timeMillis`.
    - On `Cancel`, polylines are cleared and controls unlocked,
      keeping camera position/zoom.
    - A debug overlay shows:
      - `GPS`, `DR`, `ALL` counts (`shown / DB total`).
      - Static flag from `DrDebugState` (`Static: YES/NO`).
      - DR–GPS distance (m) when both are available.
      - Latest GPS accuracy and an approximate “GPS weight Ebased on
        accuracy.
    - Draws an accuracy circle for the latest GPS sample (radius =
      `accuracy` [m], thin blue stroke and semi-transparent fill).
  - `DriveSettingsScreen`:
    - Auth method selection (Credential Manager vs AppAuth).
    - Per-method sign-in / sign-out and “Get token Eactions.
    - Folder id / URL and optional resourceKey fields with “Validate
      folder E
    - “Backup days before today Etriggers `MidnightExportWorker` and
      shows detailed status messages.
    - “Today preview Eexports today’s data to a ZIP and optionally
      uploads it; Room data is never deleted in preview mode.
  - `UploadSettingsScreen`:
    - Upload on/off, nightly vs realtime schedule, interval (seconds)
      and timezone fields, plus status hints.

- AppBar behavior:
  - In `AppRoot`:
    - Title reflects current route (“GeoLocation E “Pickup E “Map E.
    - Back button on Pickup and Map.
    - On home route, actions for `Map`, `Pickup`, `Drive`, `Upload`,
      and `ServiceToggleAction` (start/stop `GeoLocationService`).

---

## Tests, Security, and Miscellaneous

- Unit tests live under `src/test/java`; instrumentation / Compose UI
  tests under `src/androidTest/java`.
  - For Drive integration tests, mock `GoogleDriveTokenProvider` and
    HTTP clients.

- Confidential files (`local.properties`, `secrets.properties`,
  `google-services.json`, etc.) must not be committed. Use templates
  + local files.

- When changing Google auth or Drive integration behavior, ensure
  OAuth scopes and redirect URIs in README files (EN/JA/ES) match
  Cloud Console configuration.

- Use **separate** client IDs for AppAuth and Credential Manager:
  - Credential Manager  E`CREDENTIAL_MANAGER_SERVER_CLIENT_ID`
    (web/server client).
  - AppAuth  E`APPAUTH_CLIENT_ID` (installed app + custom URI
    scheme).

---

## Public API Surface (Library)

The intended stable library surface is small. Types listed here should
stay public; other types should be `internal` or treated as
implementation details where possible.

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`,
    `SettingsRepository`.

- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`.
  - `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`.

- `:datamanager`:
  - Auth and tokens:
    - `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`.
  - Drive / upload settings:
    - `DrivePrefsRepository`, `UploadPrefsRepository`,
      `UploadSchedule`.
  - Drive API:
    - `DriveApiClient`, `DriveFolderId`, `UploadResult`.
  - Upload and export:
    - `UploadEngine` (in `core.config`),
      `Uploader`, `UploaderFactory`, `GeoJsonExporter`,
      `RealtimeUploadManager`.
  - Workers / schedulers:
    - `MidnightExportWorker`, `MidnightExportScheduler`.

- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`.

- `:gps`:
  - `GpsLocationEngine`, `GpsObservation`,
    `FusedLocationGpsEngine`.

Types not listed above should be considered non-public and may change
without notice.
