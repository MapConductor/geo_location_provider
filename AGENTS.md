# Repository Guidelines

This document summarizes the common policies, coding conventions, and responsibility boundaries between modules when working on the GeoLocationProvider repository.  
Please read it once before changing code and implement in line with these guidelines.

---

## Project Structure and Modules

- The root Gradle project `GeoLocationProvider` consists of the following modules:
  - `:app` – Sample UI app built with Compose (history, Pickup, Drive settings, manual backup, etc.).
  - `:core` – Location acquisition service (`GeoLocationService`) and sensor-related logic. Delegates persistence to `:storageservice`.
  - `:storageservice` – Room `AppDatabase`, DAOs, and the `StorageService` facade. Manages location logs and export status centrally.
  - `:dataselector` – Selection logic for Pickup, etc. Filters `LocationSample` by conditions and builds representative sample rows (`SelectedSlot`).
  - `:datamanager` – Handles GeoJSON export, ZIP compression, `MidnightExportWorker` / `MidnightExportScheduler`, and Google Drive integration.
  - `:deadreckoning` – Dead Reckoning engine and API. Used from `GeoLocationService`.
  - `:auth-appauth` – Library module providing an AppAuth-based `GoogleDriveTokenProvider` implementation.
  - `:auth-credentialmanager` – Library module providing a Credential Manager + Identity-based `GoogleDriveTokenProvider` implementation.

- High-level dependency directions:
  - `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, auth modules.
  - `:core` → `:storageservice`, `:deadreckoning`.
  - `:datamanager` → `:storageservice`, Drive integration classes.
  - `:dataselector` → only the `LocationSampleSource` abstraction (the concrete implementation lives on the `:app` side, wrapping `StorageService`).

- Production code lives in each module’s `src/main/java`, `src/main/kotlin`, and `src/main/res`.  
  Build outputs are generated under each module’s `build/` directory.

- Local, machine-specific settings are stored in the root `local.properties`.  
  Its template is `local.default.properties`.  
  Default values for secrets and auth-related configuration are in `secrets.properties` (be mindful of which files are committed to Git and which are not).

---

## Build, Test, and Development Commands

- Representative Gradle commands (run from the root):
  - `./gradlew :app:assembleDebug` – Build the sample app Debug APK.
  - `./gradlew :core:assemble` / `./gradlew :storageservice:assemble` – Build the library modules.
  - `./gradlew lint` – Run Android / Kotlin static analysis.
  - `./gradlew test` / `./gradlew :app:connectedAndroidTest` – Run unit tests / UI & instrumentation tests.

- For day-to-day development, build and run from Android Studio.  
  Use command-line Gradle mainly for CI and verification.

---

## Coding Style and Naming Conventions

- Main technologies: Kotlin, Jetpack Compose, Gradle Kotlin DSL.  
  Indent with 4 spaces; assume UTF-8 for all source files.

- Package structure guidelines:
  - App and service layer: `com.mapconductor.plugin.provider.geolocation.*`
  - Storage layer: `com.mapconductor.plugin.provider.storageservice.*`
  - Dead Reckoning: `com.mapconductor.plugin.provider.geolocation.deadreckoning.*`

- Naming conventions:
  - Classes / objects / interfaces – PascalCase
  - Variables / properties / functions – camelCase
  - Constants – UPPER_SNAKE_CASE
  - Compose screen Composables – end with `Screen`
  - ViewModels – end with `ViewModel`
  - Workers / Schedulers – end with `Worker` / `Scheduler`

- Functions should have a single, focused responsibility and descriptive names that prioritize readability.

- Remove unused imports and dead code when you find them.

- For KDoc / comments, it is recommended to clearly describe:
  - Role / responsibility
  - Design policy
  - Intended usage
  - Contract (what callers can and cannot expect)

---

## Layering and Responsibilities

### StorageService / Room Access

- Access to Room `AppDatabase` / DAOs should go through `StorageService`.
  - The only exception is inside the `:storageservice` module itself (for DAO implementations and `AppDatabase`).
  - Do not import DAO types directly from `:app`, `:core`, `:datamanager`, or `:dataselector`.

- Main `StorageService` contracts:
  - `latestFlow(ctx, limit)` – Flow of the latest `limit` `LocationSample`s, ordered newest first.
  - `getAllLocations(ctx)` – Get all locations in ascending `timeMillis` order. Intended for small datasets (bulk export or previews).
  - `getLocationsBetween(ctx, from, to, softLimit)` – Get locations in `[from, to)` half-open interval, ascending `timeMillis`.  
    Use this API for large datasets.
  - `insertLocation(ctx, sample)` – Insert one sample and log before/after counts with the `DB/TRACE` tag.
  - `deleteLocations(ctx, items)` – No-op for an empty list; propagates exceptions directly.
  - `ensureExportedDay` / `oldestNotUploadedDay` / `markExportedLocal` / `markUploaded` / `markExportError` – Manage daily export status.

- All DB access is assumed to run on `Dispatchers.IO`.  
  Callers do not need to switch dispatchers again, but must avoid blocking the UI thread.

### dataselector / Pickup

- The `:dataselector` module depends only on the `LocationSampleSource` interface and must not know about Room / `StorageService` directly.
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)` uses a half-open interval `[from, to)`.

- `SelectorRepository` policies:
  - When `intervalSec == null`: direct extraction (no grid). Performs simple thinning and sorting only.
  - When `intervalSec != null`: grid-snapping mode:
    - Let `T = intervalSec * 1000L`. For each grid, select exactly one representative sample within a `±T/2` window (if multiple, prefer the earlier sample).
    - For `SortOrder.NewestFirst`:
      - Grids are generated from the end using `buildTargetsFromEnd(from, to, T)`, based on **To (endInclusive)**, and no grids are created before `from`.
      - Results are finally reversed with `slots.asReversed()` to display newest first.
    - For `SortOrder.OldestFirst`:
      - Grids are generated from the start using `buildTargetsFromStart(from, to, T)`, based on **From (startInclusive)**, and no grids are created beyond `to`.
      - Results are displayed as-is in ascending order (oldest → newest).

- `SelectorPrefs` persists Pickup conditions.  
  It uses the same units as `SelectorCondition` (from/to in milliseconds, `intervalSec` in seconds).

- UI (`:app`) uses dataselector via `SelectorUseCases.buildSelectedSlots(context)`.
  - `PickupScreen` uses this use case to get Pickup results without knowing DB/DAO types.

### GeoLocationService / Dead Reckoning

- Actual location acquisition is handled by `GeoLocationService` in `:core`, which runs as a foreground service (FGS).
  - GNSS: uses `FusedLocationProviderClient` and `LocationRequest`.
  - IMU / Dead Reckoning: uses `HeadingSensor`, `GnssStatusSampler`, `DeadReckoning`.
  - Settings: subscribes to `SettingsRepository.intervalSecFlow` / `drIntervalSecFlow` to dynamically update intervals.

- Dead Reckoning API (module `:deadreckoning`):
  - Public interfaces: `DeadReckoning`, `GpsFix`, `PredictedPoint` (`...deadreckoning.api` package).
  - Configuration: `DeadReckoningConfig` – parameter object that aggregates:
    - `staticAccelVarThreshold`
    - `staticGyroVarThreshold`
    - `processNoisePos`
    - `velocityGain`
    - `windowSize`
    - etc.
  - Factory: `DeadReckoningFactory.create(context, config = DeadReckoningConfig())`
    - Callers create `DeadReckoning` without depending on implementation details.
  - Implementation (`DeadReckoningImpl`) and engine (`DeadReckoningEngine`, `SensorAdapter`, `DrState`, `DrUncertainty`) are internal and may be replaced in the future.

- In `GeoLocationService`:
  - Create the DR instance via `DeadReckoningFactory.create(applicationContext)` and wire it to `start()` / `stop()`.
  - Insertion policy for DR samples and coordination with GNSS samples (locking, etc.) must remain outside of the `DeadReckoning` API.

### Settings Handling (SettingsRepository)

- Sampling and DR intervals are managed by `storageservice.prefs.SettingsRepository`.
  - `intervalSecFlow(context)` / `drIntervalSecFlow(context)` return flows in **seconds**, handling defaults and minimum values.
  - `currentIntervalMs(context)` / `currentDrIntervalSec(context)` are provided for backward compatibility,  
    but new code should prefer the Flow-based APIs.

---

## Auth and Drive Integration

### GoogleDriveTokenProvider and Implementations

- The main authentication interface for Drive integration is `GoogleDriveTokenProvider`.
  - New code should generally use either `CredentialManagerTokenProvider` (`:auth-credentialmanager`) or `AppAuthTokenProvider` (`:auth-appauth`) as implementations.
  - The legacy `GoogleAuthRepository` (GoogleAuthUtil-based) exists only for backward compatibility and must not be used for new features.

- Policies for `GoogleDriveTokenProvider` implementations:
  - Returned token strings must **not** include the `"Bearer "` prefix (it is added by the Authorization header builder).
  - For “normal” failures (network errors, not signed-in, missing consent, etc.), do **not** throw exceptions.  
    Instead: log the failure and return `null`.
  - If a UI flow is required, the provider itself must not launch UI.  
    It should return `null` and delegate the decision to the app layer (Activity / Compose).

### Credential Manager Auth

- `CredentialManagerAuth` is a singleton wrapper around `CredentialManagerTokenProvider`.
  - It receives `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` via `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID`  
    and treats it as the Google Identity **web / server client ID**.
  - This ID is for web/server-side use and is different from the AppAuth client ID.

### AppAuth Auth (AppAuthTokenProvider / AppAuthAuth)

- `AppAuthTokenProvider` is the `GoogleDriveTokenProvider` implementation in the `:auth-appauth` module.

- `AppAuthAuth` is its singleton wrapper, initialized as:
  - `clientId = BuildConfig.APPAUTH_CLIENT_ID`
  - `redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"`

- `APPAUTH_CLIENT_ID` is generated from the `APPAUTH_CLIENT_ID` entry in `secrets.properties` via `secrets-gradle-plugin`.

#### Cloud Console Assumptions

- The AppAuth client ID must be created as an **installed application (Android / other installed apps)**.
  - Do not reuse the Credential Manager `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (web / server client).

- Required settings for the AppAuth client:
  - Allow the custom URI scheme `com.mapconductor.plugin.provider.geolocation`.
  - Register the redirect URI:  
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect`

#### AppAuthSignInActivity

- `AppAuthSignInActivity` is a transparent Activity that starts the AppAuth sign-in flow and receives the result.
  - It opens the browser / Custom Tab via `buildAuthorizationIntent()` and passes the callback Intent to `handleAuthorizationResponse()`.

- In the manifest:
  - `android:exported="true"`
  - Intent filter:
    - scheme: `com.mapconductor.plugin.provider.geolocation`
    - path: `/oauth2redirect`

### DriveTokenProviderRegistry / Background

- For background uploads (e.g., `MidnightExportWorker`):
  - Never start UI flows; if `GoogleDriveTokenProvider.getAccessToken()` returns `null`, treat it as “not authorized”.
  - The worker should delete the ZIP file but not the Room records, and store an error message in `ExportedDay.lastError`.

- Register background providers via `DriveTokenProviderRegistry.registerBackgroundProvider(...)`.  
  Background upload logic must always obtain its provider through this registry.

- `DriveTokenProviderRegistry` behaves like a singleton within the app process.
  - In `App.onCreate()`, call `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))`.

### Drive Settings Persistence (AppPrefs / DrivePrefsRepository)

- Drive-related settings are persisted through two systems:
  - `core.prefs.AppPrefs` – legacy SharedPreferences-based settings, keeping `UploadEngine` and `folderId`.  
    Used mainly for workers and legacy paths.
  - `datamanager.prefs.DrivePrefsRepository` – newer DataStore-based settings, managing `folderId`, `resourceKey`, `accountEmail`,  
    `uploadEngine`, `authMethod`, `tokenUpdatedAtMillis`, etc., as Flows.

- New code guidelines:
  - For UI / UseCase reading of Drive settings, prefer `DrivePrefsRepository`.  
    Use `AppPrefs` only as a compatibility layer for workers, etc.
  - On settings confirmation (e.g., `DriveSettingsViewModel.validateFolder()`), also propagate to `AppPrefs.saveFolderId` / `saveEngine`  
    so legacy paths share the same configuration.

---

## WorkManager / MidnightExportWorker

- `MidnightExportWorker` is responsible for processing “backlog up to the previous day”.
  - It calculates dates using `ZoneId.of("Asia/Tokyo")` and handles one-day records in the `[0:00, 24:00)` interval (milliseconds).
  - On first run, it seeds the past 365 days of `ExportedDay` records via `StorageService.ensureExportedDay`.
  - For each day, it obtains `LocationSample` records with `StorageService.getLocationsBetween` and converts them to GeoJSON + ZIP via `GeoJsonExporter.exportToDownloads`.

- Upload and deletion policy:
  - Once local ZIP output succeeds, call `markExportedLocal` (even if the day is empty).
  - For Drive upload, check the current settings (`AppPrefs.snapshot`) and only upload when both engine and folder ID are configured.
  - On success, call `markUploaded`. On HTTP errors or auth failures, call `markExportError` and store a `lastError` message.
  - Delete the ZIP file in all cases (success or failure) to avoid filling up local storage.
  - Only when upload succeeds and there are records for that day, call `StorageService.deleteLocations` to delete that day’s records.

---

## UI / Compose Guidelines (`:app`)

- The UI layer uses Jetpack Compose and follows these patterns:
  - Screen-level Composables: `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen`, etc.
  - `ViewModel`s are created via `viewModel()` or `AndroidViewModel`, and use `viewModelScope` for async work.
  - State is exposed via StateFlow / `uiState` and passed to Compose.

- `App` / `MainActivity` / `AppRoot`:
  - In the `App` application class, call  
    `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))`  
    in `onCreate()` to register a Drive token provider for background use.
  - Also in `App.onCreate()`, call `MidnightExportScheduler.scheduleNext(this)`  
    to schedule the daily export worker.
  - In `MainActivity`, request permissions via `ActivityResultContracts.RequestMultiplePermissions` and start `GeoLocationService` after they are granted.
  - Navigation uses a two-level `NavHost` structure:
    - The `NavHost` directly under the Activity has `"home"` and `"drive_settings"` destinations.  
      The AppBar’s Drive menu navigates to the Drive settings screen.
    - The `NavHost` inside `AppRoot` has `"home"` and `"pickup"` destinations.  
      It switches between the Home and Pickup screens (via the AppBar’s Pickup button).
  - The AppBar provides navigation to Drive settings and Pickup screens.  
    The Start/Stop toggle for the service is encapsulated in `ServiceToggleAction`.

---

## Tests, Security, and Miscellaneous

- Unit tests live under `src/test/java`; instrumentation / Compose UI tests under `src/androidTest/java`.
  - For Drive integration tests, mock `GoogleDriveTokenProvider` and the HTTP client.

- Confidential files (`local.properties`, `secrets.properties`, `google-services.json`, etc.) must not be committed.  
  Manage them via templates + local files.

- When changing Google auth or Drive integration behavior, ensure that OAuth scopes and redirect URIs in the README files (EN/JA/ES)  
  match the configuration in the Cloud Console.

- Use **separate** client IDs for AppAuth and Credential Manager:
  - Credential Manager – `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (web / server client).
  - AppAuth – `APPAUTH_CLIENT_ID` (installed app + custom URI scheme).
  - Mixing them can cause `invalid_request` errors (e.g., “Custom URI scheme is not enabled / not allowed”).

---

## Public API Surface (Library)

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`
  - `LocationSampleSource`, `SelectorRepository`, `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`:
  - Auth and tokens: `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - Drive settings: `DrivePrefsRepository`
  - Drive API: `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - Upload and export: `Uploader`, `UploaderFactory`, `GeoJsonExporter`
  - Workers: `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`

Types not listed here should stay `internal` or otherwise non-public whenever possible  
so that the binary public surface remains small and stable for library consumers.

