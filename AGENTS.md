# Repository Guidelines

This document summarizes common policies, coding conventions, and responsibility boundaries between modules in the GeoLocationProvider repository.

If you change behavior across module boundaries, update this file first, then update the relevant module README(s).

---

## Documentation Map

- Root overviews:
  - `README.md` (EN)
  - `README_JA.md` (JA)
  - `README_ES.md` (ES)
- Per-module docs (preferred for "how to use across module boundaries"):
  - Each module directory contains:
    - `README.md` (EN)
    - `README_JP.md` (JP)
    - `README_ES.md` (ES)

---

## Project Structure and Modules

Root Gradle project: `GeoLocationProvider`

Modules:

- `:app`: Jetpack Compose sample app (history, pickup, map, drive settings, upload settings).
- `:core`: Foreground location service (`GeoLocationService`) and runtime orchestration (GPS, optional correction, optional dead reckoning, persistence).
- `:gps`: GPS abstraction (`GpsLocationEngine`, `GpsObservation`) and the current default engine (`LocationManagerGpsEngine`).
- `:storageservice`: Room database, entities/DAOs (internal), and the `StorageService` facade. Also `SettingsRepository` (DataStore) for sampling/DR settings.
- `:dataselector`: Pickup selection logic based on `LocationSampleSource` and `SelectorCondition` (no Room dependency).
- `:datamanager`: Export (GeoJSON/GPX, optional ZIP), upload to Google Drive, and background scheduling (`MidnightExportWorker`, `RealtimeUploadManager`).
- `:deadreckoning`: IMU-based dead reckoning engine and public API (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`).
- `:auth-appauth`: AppAuth-based `GoogleDriveTokenProvider`.
- `:auth-credentialmanager`: Credential Manager + Identity-based `GoogleDriveTokenProvider`.
- `mapconductor-core-src`: Vendored MapConductor sources used only from `:app` (treat as read-only; upstream changes preferred).

High-level dependency directions:

- `:app` -> `:core`, `:dataselector`, `:datamanager`, `:storageservice`, `:deadreckoning`, `:gps`, auth modules, MapConductor
- `:core` -> `:gps`, `:storageservice`, `:deadreckoning`
- `:datamanager` -> `:core`, `:storageservice`, Drive integration
- `:dataselector` -> `LocationSampleSource` abstraction (and the shared `LocationSample` model type); implementation lives in `:app` and delegates to `StorageService`

Production code locations:

- Kotlin/Java: `src/main/java` (some modules may also use `src/main/kotlin`)
- Resources: `src/main/res`
- Build outputs: `build/`

Local-only configuration (not committed):

- `local.properties`: SDK paths and machine-specific settings
- `secrets.properties`: Secrets Gradle Plugin values (use `local.default.properties` as a template)

---

## Build, Test, and Development Commands

Run from the project root:

- Build sample app (debug): `./gradlew :app:assembleDebug`
- Install sample app (debug): `./gradlew :app:installDebug`
- Build library modules: `./gradlew :core:assemble` / `./gradlew :storageservice:assemble`
- Build sensor modules: `./gradlew :deadreckoning:assemble` / `./gradlew :gps:assemble`
- Static analysis: `./gradlew lint`
- Unit tests: `./gradlew test`
- Instrumentation tests: `./gradlew :app:connectedAndroidTest`

Use Android Studio for day-to-day development. Use the command line mainly for CI and verification.

---

## Coding Style, Naming, and Encoding

Main technologies: Kotlin, Jetpack Compose, Gradle Kotlin DSL.

Style:

- Indent with 4 spaces.
- Remove unused imports and dead code when found.

Packages (guideline):

- App/service: `com.mapconductor.plugin.provider.geolocation.*`
- GPS abstraction: `com.mapconductor.plugin.provider.geolocation.gps.*`
- Storage: `com.mapconductor.plugin.provider.storageservice.*`
- Dead Reckoning API: `com.mapconductor.plugin.provider.geolocation.deadreckoning.*`

Naming:

- Classes/objects/interfaces: PascalCase
- Functions/variables/properties: camelCase
- Constants: UPPER_SNAKE_CASE
- Screen-level Composables: `...Screen`
- ViewModels: `...ViewModel`
- Workers/schedulers: `...Worker` / `...Scheduler`

Comment and encoding policy:

- All production source files (Kotlin/Java/XML/Gradle) must use ASCII characters only.
  - Do not use non-ASCII characters in code, comments, or string literals.
- Multilingual text (Japanese/Spanish) is allowed in documentation (`*.md`) only.
- Prefer KDoc (`/** ... */`) for public APIs; use `// ...` for internal notes.

---

## Layering and Responsibilities

### StorageService / Room access (`:storageservice`)

Rules:

- Only `:storageservice` may touch Room DAO types directly.
- Other modules (`:app`, `:core`, `:datamanager`, `:dataselector`) must use `StorageService`.

Key API contracts (most important):

- `latestFlow(ctx, limit)`: newest-first, ordered by `timeMillis` descending.
- `getAllLocations(ctx)`: ascending by `timeMillis` (use only for small datasets).
- `getLocationsBetween(ctx, from, to, softLimit)`: half-open range `[from, to)`, ascending by `timeMillis`.
- `deleteLocations(ctx, items)`: batch delete; no-op on empty list.

Export status:

- `ExportedDay` rows persist per-day export/upload state for backlog processing.

Threading:

- All DB access runs on `Dispatchers.IO` inside `StorageService`. Callers should not add extra blocking on main.

### dataselector / Pickup (`:dataselector`)

Rules:

- `:dataselector` depends only on the `LocationSampleSource` abstraction (and shared model types like `LocationSample`).
- It must not know about Room/DAOs or `StorageService`.

Contract:

- `LocationSampleSource.findBetween(fromInclusive, toExclusive)` uses half-open `[from, to)` semantics and returns samples in ascending time order.

Selection behavior summary:

- `intervalSec == null`: direct extraction (no grid, no gaps).
- `intervalSec != null`: grid snapping with window `+-T/2`, gaps represented as `SelectedSlot(sample = null)`.

### GeoLocationService / GPS / Dead Reckoning (`:core`, `:gps`, `:deadreckoning`)

`GeoLocationService` (`:core`) is a foreground service that orchestrates:

- GPS observation stream via `GpsLocationEngine` (currently `LocationManagerGpsEngine` in `:gps`).
- Optional GPS correction via `GpsCorrectionEngineRegistry` (see "GPS correction" below).
- Optional IMU dead reckoning via `:deadreckoning`.
- Persistence via `StorageService` (no DAOs).

Providers persisted into `LocationSample.provider`:

- `"gps"`: raw GPS observations
- `"gps_corrected"`: corrected observations (only when correction is enabled and differs from raw)
- `"dead_reckoning"`: DR points (when enabled)

GPS notes:

- Duplicate suppression uses a compact signature to avoid flooding the DB.
- A "GPS hold" position is maintained and blended over time (jitter reduction while staying responsive).

Dead Reckoning notes (summary):

- DR is created via `DeadReckoningFactory.create(context, config)`.
- GPS fixes are submitted using the hold position as the anchor.
- Modes:
  - `DrMode.Prediction`: ticker inserts additional realtime `dead_reckoning` samples.
  - `DrMode.Completion`: backfills `dead_reckoning` samples between GPS fixes (no realtime ticker points).

### Settings handling (`SettingsRepository` in `:storageservice`)

`SettingsRepository` encapsulates sampling and DR settings in DataStore:

- `intervalSecFlow(context)`: GPS interval seconds.
- `drIntervalSecFlow(context)`: DR interval seconds.
  - `0` means "DR disabled (GPS only)".
  - `> 0` is clamped to at least 1 second.
- `drGpsIntervalSecFlow(context)`: throttle for submitting GPS fixes to DR (0 means every fix).
- `drModeFlow(context)`: Prediction vs Completion.

`GeoLocationService` observes these flows and updates behavior while running (interval changes and DR ticker changes do not require a service restart).

---

## Auth and Drive Integration (`:datamanager`, auth modules)

### GoogleDriveTokenProvider contract

- Implementations return a bare access token string (no "Bearer " prefix).
- Normal failures return `null` (not exceptions): network issues, not signed in, missing consent, etc.
- Background code must never start UI.
  - If UI is required, providers return `null` and the app triggers sign-in/consent in the foreground.

Recommended implementations:

- Credential Manager: `:auth-credentialmanager`
- AppAuth (OAuth + PKCE): `:auth-appauth`

### DriveTokenProviderRegistry and background behavior

- `DriveTokenProviderRegistry` holds a process-wide background provider.
- Background workers/managers in `:datamanager` use the registry when they need a token.

---

## Export and Upload (`:datamanager`)

Nightly export/upload (backlog up to yesterday):

- Scheduler: `MidnightExportScheduler`
- Worker: `MidnightExportWorker`
- Timezone: read from `UploadPrefsRepository.zoneId` (default is `Asia/Tokyo`).
- Each day uses `[00:00, 24:00)` in that zone (half-open).
- For each day:
  - load rows via `StorageService.getLocationsBetween(...)`
  - export as GeoJSON or GPX (optional ZIP) to Downloads/`GeoLocationProvider`
  - upload to Drive when configured
  - always delete temporary files after the attempt
  - delete that day's `LocationSample` rows only when upload succeeds

Realtime upload (snapshot upload + drain):

- Manager: `RealtimeUploadManager`
- Watches `StorageService.latestFlow(limit = 1)` when schedule is `REALTIME`.
- When an upload is due:
  - loads a snapshot (typically via `StorageService.getAllLocations(...)`)
  - writes a cache file named by the latest sample timestamp
  - uploads to Drive
  - deletes the cache file
  - on success, deletes uploaded DB rows (upload-as-drain)

Resolution rules (why settings matter):

- Drive folder id resolution:
  - prefer `DrivePrefsRepository` (DataStore)
  - fallback to `AppPrefs` snapshot (legacy SharedPreferences)
- Upload engine:
  - uploads are performed only when `AppPrefs.engine == UploadEngine.KOTLIN`

---

## UI / Compose (`:app`)

The sample app is a reference host that wires modules together:

- Starts/stops `GeoLocationService`.
- Writes sampling/DR settings via `SettingsRepository`.
- Shows history from `StorageService.latestFlow(...)`.
- Runs drive auth flows and registers a background token provider.
- Configures export/upload via `DrivePrefsRepository` and `UploadPrefsRepository`.

Map visualization conventions:

- GPS: `provider="gps"`
- GPS(EKF): `provider="gps_corrected"`
- Dead Reckoning: `provider="dead_reckoning"`

---

## Tests, Security, and Miscellaneous

- Unit tests: `src/test/java`
- Instrumentation/Compose UI tests: `src/androidTest/java`

Do not commit confidential files:

- `local.properties`, `secrets.properties`, `google-services.json`, etc.

OAuth configuration:

- Keep OAuth scopes and redirect URIs consistent across docs and Google Cloud Console config.
- Use separate client IDs for Credential Manager and AppAuth:
  - Credential Manager: `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (web/server client)
  - AppAuth: `APPAUTH_CLIENT_ID` (installed app + custom scheme redirect)

---

## Public API Surface (Library)

The intended stable library surface is small. Types listed here should stay public; other types should be `internal` when possible.

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`
  - `LocationSampleSource`, `SelectorRepository`
- `:datamanager`:
  - `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - `DrivePrefsRepository`, `UploadPrefsRepository`, `UploadSchedule`
  - `Uploader`, `UploaderFactory`, `GeoJsonExporter`, `RealtimeUploadManager`
  - `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`:
  - `GpsLocationEngine`, `GpsObservation`
