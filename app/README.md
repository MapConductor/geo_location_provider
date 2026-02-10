# :app (Sample App / Host App Reference)

Gradle module: `:app`

This is the Jetpack Compose sample application. More importantly, it is a reference "host app" that shows how the library modules (`:core`, `:storageservice`, `:datamanager`, etc.) are wired together across module boundaries.

## What you get (effects)

When you run the app on a device and press `Start`:

- A foreground location service starts (`:core`), collects GPS observations (`:gps`), and persists samples (`:storageservice`).
- Optional Dead Reckoning (DR) runs (`:deadreckoning`) and emits predicted points as additional samples.
- Optional GPS correction can be enabled (IMU-aided EKF via `ImsEkf`) and stored as `gps_corrected` samples.
- Export and upload pipelines can run (`:datamanager`):
  - nightly backlog export/upload (Worker)
  - realtime snapshot upload (manager)

## Module boundary wiring in this app

This module is the boundary between "UI + Android components" and "library modules".

- `:core` exposes `GeoLocationService` (foreground service). The app:
  - declares it in `AndroidManifest.xml`
  - starts/stops it from UI actions
  - edits sampling/DR settings via `SettingsRepository` (DataStore)
- `:datamanager` owns export/upload logic. The app:
  - provides a token provider implementation (from an auth module)
  - registers it into `DriveTokenProviderRegistry`
  - starts schedulers/managers at startup
- `:storageservice` owns Room. The app:
  - never touches DAOs directly
  - reads and writes via `StorageService`

## Prerequisites

- Android Studio (or Gradle) with JDK 17.
- A `secrets.properties` file at the repo root (not committed).
  - Use `local.default.properties` as the template.
- (Optional, for the Map screen) a Google Maps API key.
  - `AndroidManifest.xml` reads `${GOOGLE_MAPS_API_KEY}`.

## Build and run

From the repo root:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## How to use the app (and what happens)

1. Tap `Start`.
   - Effect: `GeoLocationService` begins collecting and inserting `LocationSample` rows.
2. Configure intervals on the home screen and press `Save & Apply`.
   - Effect: values are persisted in DataStore (`SettingsRepository`) and the running service updates behavior.
3. Open `Map`.
   - Effect: samples are visualized by provider category (GPS / GPS(EKF) / Dead Reckoning).
4. Open `Drive` and sign in.
   - Effect: a token provider can supply access tokens for Drive uploads.
5. Open `Upload` and enable either nightly or realtime.
   - Effect: `:datamanager` schedules/starts background processing based on your settings.

## Where to look in code (boundary examples)

- App startup wiring (auth registry + schedulers + optional correction install):
  - `app/src/main/java/com/mapconductor/plugin/provider/geolocation/App.kt`
- Service declaration and required permissions:
  - `app/src/main/AndroidManifest.xml`
- Home UI (writes to `SettingsRepository`, shows history from `StorageService.latestFlow`):
  - `app/src/main/java/com/mapconductor/plugin/provider/geolocation/ui/main/GeoLocationProviderScreen.kt`
- Drive and upload settings (writes `DrivePrefsRepository` / `UploadPrefsRepository` and triggers workers):
  - `app/src/main/java/com/mapconductor/plugin/provider/geolocation/ui/settings/DriveSettingsScreen.kt`
  - `app/src/main/java/com/mapconductor/plugin/provider/geolocation/ui/settings/UploadSettingsScreen.kt`

## Detailed boundary edits (change -> effect)

### Change which auth method is used in background uploads

What you change:

- In `App.kt`, pick a `GoogleDriveTokenProvider` implementation and register it:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

Effect:

- `:datamanager` background work (nightly/realtime) uses that provider for access tokens.
- If the provider returns `null`, uploads are skipped safely (treated as "not authorized").

### Enable/disable GPS EKF correction

What you change:

- Install and configure EKF at startup (see `:core` docs).

Effect:

- When enabled, additional corrected samples are persisted as `provider="gps_corrected"` and shown as `GPS(EKF)` in the Map UI.

### Control whether background export/upload runs

What you change:

- Call (or do not call) the two entry points at startup:
  - `MidnightExportScheduler.scheduleNext(context)`
  - `RealtimeUploadManager.start(context)`

Effect:

- Nightly export/upload and realtime uploads only run when the relevant component is started and settings permit it.

## Notes

- This is a reference implementation, not a stable library API.
- Do not commit secrets (`secrets.properties`, `google-services.json`, etc.).
