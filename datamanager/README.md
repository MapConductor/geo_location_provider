# :datamanager (Export + Upload + Drive Integration)

Gradle module: `:datamanager`

This module owns exporting location data to files (GeoJSON / GPX), scheduling background work (nightly export and realtime upload), and uploading to Google Drive using a pluggable token provider.

## Module boundary contract (what you configure -> what happens)

You configure:

- Drive auth:
  - provide a `GoogleDriveTokenProvider` implementation (from `:auth-credentialmanager` or `:auth-appauth`)
  - register it via `DriveTokenProviderRegistry`
- Preferences:
  - `DrivePrefsRepository` (folder id, auth method, account)
  - `UploadPrefsRepository` (schedule, interval, timezone, output format)
- Background start points:
  - `MidnightExportScheduler.scheduleNext(context)`
  - `RealtimeUploadManager.start(context)`

Effects:

- Nightly backup:
  - exports per-day files to Downloads/`GeoLocationProvider` (optionally as ZIP)
  - uploads to the configured Google Drive folder
  - updates per-day upload status via `:storageservice`
  - deletes temporary export files after each attempt
- Realtime upload:
  - creates a snapshot file in cache based on the latest sample timestamp
  - uploads it to Drive at the configured interval
  - on success, deletes the uploaded `LocationSample` rows (upload-as-drain behavior)

## Resolution rules (important details)

These rules explain why "writing X" results in "behavior Y".

- Schedule gate:
  - `RealtimeUploadManager` only reacts to new samples when `UploadPrefsRepository.scheduleFlow == REALTIME`.
  - `MidnightExportWorker` runs on schedule, but will skip non-nightly schedules unless invoked with force/full-scan mode.
- Drive folder resolution order:
  - Prefer `DrivePrefsRepository.folderIdFlow` (UI/DataStore value).
  - Fallback to `AppPrefs.snapshot(context).folderId` (legacy SharedPreferences).
- Upload engine:
  - Workers/managers read `AppPrefs.snapshot(context).engine` and only create an uploader when it is `UploadEngine.KOTLIN`.
- Output file naming:
  - Nightly: `glp-YYYYMMDD.zip` in Downloads/`GeoLocationProvider` (ZIP contains one `.geojson` or `.gpx`).
  - Realtime: `YYYYMMDD_HHmmss.json` or `.gpx` in `cacheDir` (deleted after the attempt).

## Recipes (what to configure -> what you get)

### Recipe: Nightly backup to Drive (per-day ZIP)

What you do:

1. Register a background token provider:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

2. Configure Drive folder (prefer DataStore repo, fallback exists via `AppPrefs`):

```kotlin
val drivePrefs = DrivePrefsRepository(appContext)
drivePrefs.setFolderId("YOUR_DRIVE_FOLDER_ID")
```

3. Configure upload schedule and output:

```kotlin
val uploadPrefs = UploadPrefsRepository(appContext)
uploadPrefs.setSchedule(UploadSchedule.NIGHTLY)
uploadPrefs.setOutputFormat(UploadOutputFormat.GEOJSON)
uploadPrefs.setZoneId("Asia/Tokyo")
```

4. Schedule the worker:

```kotlin
MidnightExportScheduler.scheduleNext(appContext)
```

What happens:

- `MidnightExportWorker` runs around local midnight (scheduler uses a fixed zone for timing).
- For each day in the backlog (up to yesterday):
  - records are loaded from Room via `StorageService.getLocationsBetween(...)`
  - a ZIP is exported to Downloads/`GeoLocationProvider` as `glp-YYYYMMDD.zip`
  - the ZIP is uploaded to the Drive folder when engine and folder are configured
  - the local ZIP is deleted after the attempt
  - on upload success, that day's `LocationSample` rows are deleted (database is drained per day)

### Recipe: Manual "run backlog now"

What you do:

```kotlin
MidnightExportWorker.runNow(appContext)
```

What happens:

- A one-time worker is enqueued immediately and performs a full scan of applicable days.

### Recipe: Realtime upload (snapshot upload + drain)

What you do:

1. Configure schedule:

```kotlin
val uploadPrefs = UploadPrefsRepository(appContext)
uploadPrefs.setSchedule(UploadSchedule.REALTIME)
uploadPrefs.setIntervalSec(60) // 0 means "every sample"
```

2. Start the manager once per process:

```kotlin
RealtimeUploadManager.start(appContext)
```

What happens:

- The manager observes `StorageService.latestFlow(limit=1)`.
- When a new sample arrives and schedule is `REALTIME`:
  - a snapshot file is generated in `cacheDir` named like `YYYYMMDD_HHmmss.json` or `.gpx`
  - the snapshot is uploaded to Drive
  - the cache file is deleted
  - on success, all uploaded DB rows are deleted (upload-as-drain)

## Minimal end-to-end wiring (host code -> visible outcomes)

If you configure the following items, you should observe exports/uploads happening without needing to read internal code.

### 1) Register a background token provider (auth -> Drive access)

What you write:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

What you get:

- Workers and managers can obtain access tokens without UI.
- If the provider returns `null`, uploads are skipped and treated as "not authorized yet".

### 2) Enable an upload engine (engine -> uploader creation)

What you write (legacy compatibility layer used by workers/managers):

```kotlin
AppPrefs.saveEngine(context, UploadEngine.KOTLIN)
```

What you get:

- Workers/managers will create an uploader. If engine is not `KOTLIN`, uploads are skipped by design.

### 3) Configure the Drive folder (folder id -> upload target)

What you write (preferred):

```kotlin
DrivePrefsRepository(context).setFolderId("YOUR_DRIVE_FOLDER_ID")
```

Fallback (legacy):

```kotlin
AppPrefs.saveFolderId(context, "YOUR_DRIVE_FOLDER_ID_OR_URL")
```

What you get:

- Uploads target that folder. If the folder id is empty, uploads are skipped.

### 4) Choose a schedule (schedule -> when work runs)

What you write:

- Nightly:
  - `UploadPrefsRepository(context).setSchedule(UploadSchedule.NIGHTLY)`
  - call `MidnightExportScheduler.scheduleNext(context)`
- Realtime:
  - `UploadPrefsRepository(context).setSchedule(UploadSchedule.REALTIME)`
  - call `RealtimeUploadManager.start(context)`

What you get:

- Nightly: per-day ZIPs are exported and uploaded (backlog up to yesterday).
- Realtime: on new DB inserts, snapshots are uploaded and (on success) uploaded rows are deleted.

## Troubleshooting (symptom -> likely cause)

- "Nothing uploads":
  - token provider not registered, or `getAccessToken()` returns `null`
  - Drive folder id is empty (DrivePrefs/AppPrefs)
  - `AppPrefs.engine != UploadEngine.KOTLIN`
  - schedule is `NONE`, or the relevant entry point is not started (scheduler/manager)
- "Nightly does not run":
  - `MidnightExportScheduler.scheduleNext(context)` not called, or OS background limits prevent WorkManager from running
- "Realtime does not react":
  - `RealtimeUploadManager.start(context)` not called, or schedule is not `REALTIME`
- "DB rows are not deleted":
  - deletion happens only after successful upload (upload-as-drain)

## Main components

### Exporters

- `GeoJsonExporter`: writes `.geojson` or a `.zip` containing one GeoJSON file
- `GpxExporter`: writes `.gpx` or a `.zip` containing one GPX file

Files:

- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/export/GeoJsonExporter.kt`
- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/export/GpxExporter.kt`

### Drive auth abstraction

- `GoogleDriveTokenProvider`: the only auth interface the upload layer depends on
- `DriveTokenProviderRegistry`: process-wide registry used by Workers/background code

Files:

- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/drive/auth/GoogleDriveTokenProvider.kt`
- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/drive/auth/DriveTokenProviderRegistry.kt`

Example implementations live in:

- `../auth-credentialmanager/README.md`
- `../auth-appauth/README.md`

### Uploaders

- `UploaderFactory`: creates an `Uploader` based on `UploadEngine` and an optional token provider

File:

- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/drive/upload/DriveUploader.kt`

### Preferences

DataStore repositories used by UI and workers:

- `DrivePrefsRepository`: Drive folder and auth-related state
- `UploadPrefsRepository`: schedule, interval, timezone, and output format

Files:

- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/prefs/DrivePrefsRepository.kt`
- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/prefs/UploadPrefsRepository.kt`

### Background work

- `MidnightExportWorker` + `MidnightExportScheduler`: exports backlog up to "yesterday" and uploads ZIPs
- `RealtimeUploadManager`: watches latest samples and uploads snapshots on a schedule

Files:

- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/work/MidnightExportWorker.kt`
- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/work/MidnightExportScheduler.kt`
- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/work/RealtimeUploadManager.kt`

## Typical integration

1. Provide and register a background token provider at startup:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(myProvider)
```

2. Schedule nightly export and start realtime manager (depending on settings):

```kotlin
MidnightExportScheduler.scheduleNext(appContext)
RealtimeUploadManager.start(appContext)
```

## Notes

- Background workers must not start UI. If auth is required, token providers must return `null` and let the app handle sign-in in the foreground.
- Export ranges use half-open `[from, to)` semantics and are timezone-aware via upload preferences.
- Temporary files created for uploads should be deleted after each attempt.
