# GeoLocationProvider

GeoLocationProvider is an Android multi-module SDK and sample app for recording, storing, exporting, and uploading location data.

At a high level, it:

- records location samples into a Room database
- optionally generates Dead Reckoning (DR) samples
- exports data as GeoJSON or GPX (optionally zipped)
- uploads exports/snapshots to Google Drive (nightly backup and optional realtime upload)

Translations:

- Japanese: `README_JA.md`
- Spanish: `README_ES.md`

## Where to start

- Run the sample app: start with this README, then `app/README.md`.
- Reuse the SDK in your own app: start with `core/README.md` and `storageservice/README.md`.
- Export/upload to Drive: see `datamanager/README.md` and one of the auth modules.

## Quick start (sample app)

Prerequisites:

- Android Studio (or Gradle) with JDK 17
- Android SDK installed
- a local `secrets.properties` file at the repo root (not committed)
  - use `local.default.properties` as a template

Build and install:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Basic flow in the app:

1. Press `Start` to run the foreground tracking service.
2. Set GPS/DR intervals and press `Save & Apply`.
3. Use `Map` to visualize providers (GPS / GPS(EKF) / Dead Reckoning).
4. Use `Drive` to sign in and set a Drive folder.
5. Use `Upload` to enable nightly or realtime.

## Documentation map

Root docs:

- Repository guidelines and module responsibilities: `AGENTS.md` (also `AGENTS_JA.md`, `AGENTS_ES.md`)
- Overview: `README.md` (EN), `README_JA.md` (JA), `README_ES.md` (ES)

Per-module docs (recommended for "how to use across module boundaries"):

- `app/README.md` (JP: `app/README_JP.md`, ES: `app/README_ES.md`)
- `core/README.md` (JP: `core/README_JP.md`, ES: `core/README_ES.md`)
- `storageservice/README.md` (JP: `storageservice/README_JP.md`, ES: `storageservice/README_ES.md`)
- `gps/README.md` (JP: `gps/README_JP.md`, ES: `gps/README_ES.md`)
- `deadreckoning/README.md` (JP: `deadreckoning/README_JP.md`, ES: `deadreckoning/README_ES.md`)
- `dataselector/README.md` (JP: `dataselector/README_JP.md`, ES: `dataselector/README_ES.md`)
- `datamanager/README.md` (JP: `datamanager/README_JP.md`, ES: `datamanager/README_ES.md`)
- `auth-credentialmanager/README.md` (JP: `auth-credentialmanager/README_JP.md`, ES: `auth-credentialmanager/README_ES.md`)
- `auth-appauth/README.md` (JP: `auth-appauth/README_JP.md`, ES: `auth-appauth/README_ES.md`)

Note: module Japanese docs use the filename `README_JP.md`, while the root Japanese overview is `README_JA.md`.

## Architecture at a glance

Data flow:

1. `:gps` produces `GpsObservation` values (currently via `LocationManagerGpsEngine`).
2. `:core` (`GeoLocationService`) converts observations into `LocationSample` rows and inserts them via `:storageservice`.
3. `:deadreckoning` can be used by `:core` to generate additional DR samples (`provider="dead_reckoning"`).
4. `:datamanager` exports and uploads:
   - nightly worker: per-day ZIP to Downloads and Drive
   - realtime manager: snapshot file in cache and Drive (then delete uploaded DB rows)
5. Auth modules implement `GoogleDriveTokenProvider` and are registered via `DriveTokenProviderRegistry`.

Module boundary rule of thumb:

- Only `:storageservice` touches Room DAOs directly.
- Other modules must use `StorageService` and the documented contracts (ordering, ranges, error behavior).

## Local configuration files

- `local.properties` (not committed): Android SDK path and local settings
- `secrets.properties` (not committed): Secrets Gradle Plugin values, for example:

```properties
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

## Build and verification

```bash
./gradlew test
./gradlew lint
```

## Development notes

- Production source files are ASCII-only to avoid encoding issues (documentation can be multilingual).
- Do not commit secrets (`secrets.properties`, `google-services.json`, etc.).
