# GeoLocationProvider

GeoLocationProvider is an Android SDK and sample application for **recording, storing, exporting, and uploading location data**.  
It records background location to a Room database, exports it as GeoJSON+ZIP, and can upload the results to **Google Drive**.

---

## Features

- Background location acquisition (configurable sampling interval)
- Storage in a Room database (`LocationSample`, `ExportedDay`)
- Export to GeoJSON format with ZIP compression
- Automatic daily export at midnight (`MidnightExportWorker`)
- Manual export for yesterday’s backup and today’s preview
- Pickup feature (extract representative samples by period / count)
- Google Drive upload with folder selection

---

## Architecture

### Module Structure

This repository is organized as a multi-module Gradle project:

- **`:app`** – Jetpack Compose sample UI (history, Pickup, Drive settings, manual export).
- **`:core`** – Location acquisition (`GeoLocationService`), sensor handling, and sampling interval application. Persists to `:storageservice` and uses `:deadreckoning`.
- **`:storageservice`** – Room `AppDatabase` / DAOs and `StorageService` facade. Single entry point to the database for location logs and export status.
- **`:dataselector`** – Pickup selection logic. Filters `LocationSample` by conditions and builds representative rows (`SelectedSlot`).
- **`:datamanager`** – GeoJSON export, ZIP compression, `MidnightExportWorker` / `MidnightExportScheduler`, and Google Drive integration.
- **`:deadreckoning`** – Dead Reckoning engine and public API (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`).
- **`:auth-appauth`** – AppAuth-based implementation of `GoogleDriveTokenProvider`.
- **`:auth-credentialmanager`** – Credential Manager + Identity-based implementation of `GoogleDriveTokenProvider`.

High-level dependency directions:

- `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, auth modules  
- `:core` → `:storageservice`, `:deadreckoning`  
- `:datamanager` → `:storageservice`, Drive integration  
- `:dataselector` → `LocationSampleSource` abstraction only (concrete implementation lives in `:app`, wrapping `StorageService`)

### Key Components

- **Entities: `LocationSample` / `ExportedDay`**  
  `LocationSample` stores latitude, longitude, timestamp, speed, battery level, etc.  
  `ExportedDay` tracks export status per day (local export, upload result, last error).

- **`StorageService`**  
  Single facade around Room (`AppDatabase` / DAOs). All modules access the database through `StorageService` rather than DAOs directly.

- **Pickup (`:dataselector`)**  
  `SelectorCondition`, `SelectorRepository`, `BuildSelectedSlots` and `SelectorPrefs` implement the Pickup feature.  
  They thin and snap samples to a time grid and represent gaps as `SelectedSlot(sample = null)`.  
  `LocationSampleSource` abstracts where samples come from; the sample app implements it using `StorageService`.

- **Dead Reckoning (`:deadreckoning`)**  
  A public API (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`) and an internal engine (`DeadReckoningEngine`, `DeadReckoningImpl`, etc.).  
  `GeoLocationService` creates DR instances via `DeadReckoningFactory.create(applicationContext)` and controls them with `start()` / `stop()`.

- **Midnight export (`MidnightExportWorker` / `MidnightExportScheduler`)**  
  WorkManager-based pipeline that:
  - Scans days up to “yesterday” in `Asia/Tokyo`
  - Exports each day to GeoJSON+ZIP via `GeoJsonExporter`
  - Marks local export (`markExportedLocal`)
  - Optionally uploads to Drive using `UploaderFactory` and `GoogleDriveTokenProvider`
  - Records upload status with `markUploaded` / `markExportError`
  - Deletes ZIP files in all cases and deletes old samples after successful upload

- **Drive integration (`GoogleDriveTokenProvider` and friends)**  
  `GoogleDriveTokenProvider` abstracts access tokens for Drive.  
  `DriveTokenProviderRegistry` provides a background token provider for workers.  
  `Uploader` / `UploaderFactory` (backed by `KotlinDriveUploader`) perform uploads using `DriveApiClient`.  
  `GoogleAuthRepository` (GoogleAuthUtil-based) remains only for backward compatibility and is deprecated for new code.

- **UI (Compose) – `MainActivity`, `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen`**  
  The app has a two-level `NavHost`:
  - Activity-level: `"home"` and `"drive_settings"` (Drive settings from the AppBar menu)
  - App-level: `"home"` and `"pickup"` (Home vs Pickup in the main AppBar)  
  Permissions are handled via `ActivityResultContracts.RequestMultiplePermissions`, and the Start/Stop toggle is encapsulated in `ServiceToggleAction`.

---

## Public API Overview

GeoLocationProvider is intended to be used both as:

- A **sample app** (module `:app`) you can run as-is
- A **set of reusable libraries** that you can depend on from your own app

When consuming the libraries, you typically depend on the following modules and types:

### Modules and main entry points

- **`:core`**
  - `GeoLocationService` – Foreground service that records `LocationSample` into the database.
  - `UploadEngine` – Enum indicating which upload engine to use.

- **`:storageservice`**
  - `StorageService` – Single facade around Room (`LocationSample`, `ExportedDay`).
  - `LocationSample`, `ExportedDay` – Primary entities for location logs and export status.
  - `SettingsRepository` – Sampling / Dead Reckoning interval configuration.

- **`:dataselector`**
  - `SelectorCondition`, `SelectedSlot`, `SortOrder` – Pickup / query domain model.
  - `LocationSampleSource` – Abstraction over the source of `LocationSample`.
  - `SelectorRepository`, `BuildSelectedSlots` – Core selection logic and a small use-case wrapper.
  - `SelectorPrefs` – DataStore facade for persisting Pickup conditions.

- **`:datamanager`**
  - `GeoJsonExporter` – Export `LocationSample` to GeoJSON + ZIP.
  - `GoogleDriveTokenProvider` – Abstraction for Drive access tokens.
  - `DriveTokenProviderRegistry` – Registry for a background token provider.
  - `Uploader`, `UploaderFactory` – High-level Drive upload entry points.
  - `DriveApiClient`, `DriveFolderId`, `UploadResult` – Drive REST helpers and result types.
  - `MidnightExportWorker`, `MidnightExportScheduler` – Daily export pipeline.

- **`:deadreckoning`**
  - `DeadReckoning`, `GpsFix`, `PredictedPoint` – DR engine API used by `GeoLocationService`.
  - `DeadReckoningConfig`, `DeadReckoningFactory` – Configuration object and factory for creating DR instances.

- **`:auth-credentialmanager` / `:auth-appauth`** (optional)
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider` – Reference implementations of `GoogleDriveTokenProvider`.

All other types (DAO and `AppDatabase` definitions, repository implementations, low-level HTTP helpers, etc.) are **internal details** and may change without notice.

---

## Minimal Integration Examples

The following snippets illustrate end-to-end integration in your own app.

### 1. Start the location service and observe history

```kotlin
// In your Activity
private fun startLocationService() {
    val intent = Intent(this, GeoLocationService::class.java)
        .setAction(GeoLocationService.ACTION_START)
    ContextCompat.startForegroundService(this, intent)
}

private fun stopLocationService() {
    val intent = Intent(this, GeoLocationService::class.java)
        .setAction(GeoLocationService.ACTION_STOP)
    startService(intent)
}
```

```kotlin
// In a ViewModel
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    val latest: StateFlow<List<LocationSample>> =
        StorageService.latestFlow(app.applicationContext, limit = 100)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
```

### 2. Run Pickup (selection) on stored samples

```kotlin
// Build a LocationSampleSource backed by StorageService
class StorageSampleSource(
    private val context: Context
) : LocationSampleSource {
    override suspend fun findBetween(fromInclusive: Long, toExclusive: Long): List<LocationSample> {
        return StorageService.getLocationsBetween(context, fromInclusive, toExclusive)
    }
}

suspend fun runPickup(context: Context): List<SelectedSlot> {
    val source = StorageSampleSource(context.applicationContext)
    val repo = SelectorRepository(source)
    val useCase = BuildSelectedSlots(repo)

    val cond = SelectorCondition(
        fromMillis = /* startMs */,
        toMillis = /* endMs */,
        intervalSec = 60,         // 60‑second grid
        limit = 1000,
        order = SortOrder.OldestFirst
    )
    return useCase(cond)
}
```

### 3. Configure Drive upload and daily export

```kotlin
// In your Application class
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Example: reuse CredentialManagerTokenProvider from :auth-credentialmanager
        val provider = CredentialManagerTokenProvider(
            context = this,
            serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
        )

        // Register background provider for MidnightExportWorker
        DriveTokenProviderRegistry.registerBackgroundProvider(provider)

        // Schedule daily export at midnight
        MidnightExportScheduler.scheduleNext(this)
    }
}
```

```kotlin
// Manually upload a file to Drive using UploaderFactory (optional)
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

## Google Drive Authentication Options

All Drive uploads go through `GoogleDriveTokenProvider`. The core contract is:

- Tokens are **bare access tokens** (without `"Bearer "` prefix).
- Normal failures (network error, not signed in, missing consent, etc.) should be signalled by returning `null`, not by throwing.
- Providers must never launch UI directly from background contexts; if a UI flow is needed, return `null` and let the app decide.

You can choose one of the following implementations or provide your own.

### Option 1: AppAuth (standard OAuth 2.0 with PKCE)

This option uses [AppAuth for Android](https://github.com/openid/AppAuth-Android) to perform OAuth 2.0 for native apps.

1) Add dependency to your app module:

```gradle
implementation(project(":auth-appauth"))
```

2) Create OAuth 2.0 credentials in Google Cloud Console:

- Configure the OAuth consent screen.
- Create an **Installed application** client (Android / other installed apps), **not** a Web application client.
- Allow a custom URI scheme, for example: `com.mapconductor.plugin.provider.geolocation`.
- Register a redirect URI, e.g.:

```text
com.mapconductor.plugin.provider.geolocation:/oauth2redirect
```

3) Initialize the token provider (example):

```kotlin
val tokenProvider = AppAuthTokenProvider(
    context = applicationContext,
    clientId = BuildConfig.APPAUTH_CLIENT_ID,
    redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
)

// Start authorization from an Activity
val intent = tokenProvider.buildAuthorizationIntent()
startActivityForResult(intent, REQUEST_CODE)

// Handle response
tokenProvider.handleAuthorizationResponse(data)

// Use with uploader
val uploader = KotlinDriveUploader(context, tokenProvider)
```

In the sample project, `APPAUTH_CLIENT_ID` is provided via `secrets.properties` and `secrets-gradle-plugin`.

### Option 2: Credential Manager (modern Android auth, used by the sample app)

This option uses the Android Credential Manager + Identity APIs. It is the default path for the sample app and integrates well with Android 14+.

1) Add dependency to your app module:

```gradle
implementation(project(":auth-credentialmanager"))
```

2) Create OAuth 2.0 credentials in Google Cloud Console:

- Create **Web application** credentials.
- Use the resulting client ID as the **server client ID**.

3) Initialize the token provider:

```kotlin
val tokenProvider = CredentialManagerTokenProvider(
    context = applicationContext,
    serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
)

// Sign in (from Activity / UI)
val credential = tokenProvider.signIn()

// Get token (can also be used from background)
val token = tokenProvider.getAccessToken()
```

In the sample app:

- `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` is read from `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID`.
- That value is generated by `secrets-gradle-plugin` from the `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` key in `secrets.properties`.
- `App` registers `CredentialManagerAuth` as background provider via `DriveTokenProviderRegistry`.

### Option 3: Legacy (deprecated, backward compatibility only)

The legacy `GoogleAuthRepository` (GoogleAuthUtil-based) is still available but is **deprecated** and should not be used in new code:

```kotlin
@Deprecated("Use AppAuth or Credential Manager instead")
val tokenProvider = GoogleAuthRepository(context)
```

### Required OAuth Scopes

All implementations use the following Google Drive scopes:

- `https://www.googleapis.com/auth/drive.file` – Access to files created/opened by the app.
- `https://www.googleapis.com/auth/drive.metadata.readonly` – Used for folder ID validation and metadata.

### Custom Implementation

You can also provide your own implementation of `GoogleDriveTokenProvider`:

```kotlin
class MyCustomTokenProvider : GoogleDriveTokenProvider {
    override suspend fun getAccessToken(): String? {
        // Your custom implementation
    }

    override suspend fun refreshToken(): String? {
        // Your custom implementation
    }
}
```

---

## Quick Start

> The modules `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning`, and `auth-*` are already wired together.  
> You only need to prepare local configuration files and (optionally) Google Drive credentials to build and run the sample app.

### 0. Requirements

- Android Studio with AGP 8.1+ support (or the bundled `./gradlew`)
- JDK 17 (Gradle and Kotlin JVM target 17)
- Android SDK with API level 26+ installed (project uses `compileSdk = 36`)

### 1. Prepare `local.properties`

`local.properties` stores **developer machine–specific settings** such as the Android SDK path (normally excluded from Git).  
Android Studio usually generates this file automatically; if not, create it at the project root.

**Example: `local.properties` (local environment, do not commit)**

```properties
sdk.dir=C:\\Android\\Sdk          # Example for Windows
# sdk.dir=/Users/you/Library/Android/sdk   # Example for macOS
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

### 2. Prepare `secrets.properties`

`secrets.properties` stores **authentication-related and other sensitive values** used by the `secrets-gradle-plugin`.  
The repository includes `local.default.properties` as a template; copy it and replace the values as needed:

```bash
cp local.default.properties secrets.properties   # or copy manually on Windows
```

**Example: `local.default.properties` (template, committed)**

```properties
# Credential Manager (server) client ID for Google Sign-In / Identity.
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com

# AppAuth (installed app) client ID for OAuth2 with custom scheme redirect.
# Use an "installed app" client, not the server client ID above.
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com
```

**Example: `secrets.properties` (local file, do not commit)**

```properties
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com

# Optional: Google Maps API key used by the sample app manifest.
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

For local builds, dummy values are sufficient.  
To actually sign in and upload to Drive or use Maps, configure real client IDs and API keys in `secrets.properties`.

### 3. Configure Google Drive Integration

To use Drive upload, prepare OAuth 2.0 settings in Google Cloud Console:

1. Create a project and configure the OAuth consent screen.  
2. Create OAuth client IDs:
   - A **Web application** client for Credential Manager (`CREDENTIAL_MANAGER_SERVER_CLIENT_ID`).
   - An **Installed app** client for AppAuth (`APPAUTH_CLIENT_ID`) using the custom scheme/redirect URI.  
3. Enable the Drive API and grant the scopes:
   - `https://www.googleapis.com/auth/drive.file`
   - `https://www.googleapis.com/auth/drive.metadata.readonly`
4. Put the client IDs in `secrets.properties` as shown above.

### 4. Add permissions (for confirmation)

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />

<!-- Optional: only if you need location access while no foreground service or UI is visible -->
<!-- <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" /> -->
```

### 5. Build

```bash
./gradlew :app:assembleDebug
```

---

## Development

- Follows Kotlin coding conventions.
- UI implemented with Jetpack Compose.
- Persistence implemented with Room + DataStore (for settings).
- Background jobs implemented with WorkManager (`MidnightExportWorker`, `MidnightExportScheduler`).
- Common formatting helpers live in utility classes such as `Formatters.kt`.

### Source encoding and comments

- All production source files in this repository (Kotlin / Java / XML / Gradle scripts, etc.) are written using **ASCII characters only**.  
  Non-ASCII characters are not used in code, comments, or string literals to avoid encoding issues across tools and platforms.
- Multilingual documentation (Japanese / Spanish) is provided separately in `README_JA.md`, `README_ES.md` and other `*.md` files.
- Comment style is unified across modules:
  - Public APIs and key classes use KDoc (`/** ... */`) to describe role, design policy, usage, and contracts.
  - Internal implementation details use simple `// ...` line comments and minimal section headers such as `// ---- Section ----`.
  - Decorative banners or inconsistent separator styles are avoided.

---

## Feature Implementation Status

| Feature                    | Status          | Notes                                             |
|----------------------------|-----------------|---------------------------------------------------|
| Location recording (Room)  | [v] Implemented | Saved in Room DB                                  |
| Daily export (GeoJSON+ZIP) | [v] Implemented | Executed at midnight by `MidnightExportWorker`    |
| Google Drive upload        | [v] Implemented | Uses Kotlin-based uploader                        |
| Pickup (interval/count)    | [v] Implemented | Uses ViewModel + UseCase + Compose UI             |
| Drive full-scope auth      | [-] In progress | Full-scope browsing/updating of existing files    |
| UI: DriveSettingsScreen    | [v] Implemented | Auth, folder settings, connectivity tests         |
| UI: PickupScreen           | [v] Implemented | Input conditions and display of extracted results |
| UI: History list           | [v] Implemented | Chronological view of saved samples               |
