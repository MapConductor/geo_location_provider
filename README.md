# GeoLocationProvider

GeoLocationProvider is an SDK & sample application for **recording, storing, and exporting location data** on Android.  
It also provides a feature to **upload exported data to Google Drive**.

---

## Features

- Background location acquisition (sampling interval configurable)
- Storage using Room database
- Export to GeoJSON format (with ZIP compression)
- Automatic export at midnight (Midnight Export Worker)
- Manual export for yesterday’s backup & today’s preview
- Pickup feature (extract representative samples by period or count)
- Google Drive upload (with folder selection)

---

## Architecture
### Module Structure

> This repository includes the following modules: `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning`, and `auth-*`.  
> You can use the bundled sample app as-is, or depend only on the modules you need from your own project.

- **:core**  
  Location acquisition (`GeoLocationService`), sensor-related logic, and applying settings such as sampling intervals.  
  Persists data via `:storageservice` and uses `:deadreckoning` for DR.

- **:storageservice**  
  Room `AppDatabase` / DAOs and the `StorageService` facade.  
  Manages location logs (`LocationSample`) and export status (`ExportedDay`) as the single entry point to the database.

- **:dataselector**  
  Data selection logic for Pickup, based on conditions such as period, interval, and limit  
  (`SelectorCondition`, `SelectedSlot`, `SelectorRepository`, `BuildSelectedSlots`).

- **:datamanager**  
  GeoJSON export, ZIP compression, `MidnightExportWorker`, and Google Drive integration  
  (`GoogleDriveTokenProvider`, `Uploader`, `DriveTokenProviderRegistry`, etc.).

- **:deadreckoning**  
  Dead Reckoning engine and API (`DeadReckoning`, `GpsFix`, `PredictedPoint`), used by `GeoLocationService`.

- **:auth-appauth / :auth-credentialmanager**  
  Reference implementations of `GoogleDriveTokenProvider` based on AppAuth and Android Credential Manager.

- **:app**  
  Jetpack Compose sample UI (history list, Pickup screen, Drive settings screen, etc.).

### Key Components

- **LocationSample / ExportedDay Entities**  
  Core data models for collected location samples. `LocationSample` stores latitude, longitude, timestamp, battery level, etc.  
  `ExportedDay` records which dates have already been exported and their upload status.

- **StorageService**  
  Single facade to Room (`AppDatabase` / DAOs). Other modules access the database only via `StorageService`, never directly via DAOs.

- **Pickup / dataselector**  
  `SelectorCondition`, `SelectorRepository`, and `BuildSelectedSlots` form the core of the Pickup logic.  
  They thin and snap samples to a time grid and represent gaps as `SelectedSlot(sample = null)`.

- **MidnightExportWorker & MidnightExportScheduler**  
  Background tasks using WorkManager. Every day at midnight, they export the previous day’s data to GeoJSON + ZIP and upload it to Google Drive. The scheduler handles booking the next execution.

- **GoogleDriveTokenProvider / Uploader / DriveApiClient**  
  Core building blocks for Google Drive integration. `GoogleDriveTokenProvider` abstracts access tokens,  
  `Uploader` / `UploaderFactory` (backed by `KotlinDriveUploader`) perform the uploads, and `DriveApiClient` handles REST calls.  
  A legacy `GoogleAuthRepository` (GoogleAuthUtil‑based) is kept for backward compatibility but should not be used in new code.

- **UI Screens (MainActivity, PickupScreen, DriveSettingsScreen, etc.)**  
  User interface implemented with Jetpack Compose.
    - `MainActivity`: Entry point for starting/stopping the service and showing history
    - `PickupScreen`: Extracts and displays samples based on user‑specified conditions using dataselector
    - `DriveSettingsScreen`: Handles Google Drive authentication, folder selection, and connection testing

---

## Public API Overview

This project is designed as a reusable library plus a sample app. When embedding it into your own app, you typically depend on the following modules and types:

### Modules and main entry points

- **`:core`**
  - `GeoLocationService` – Foreground service that records `LocationSample` into the database.
  - `UploadEngine` – Enum used when configuring export/upload.
- **`:storageservice`**
  - `StorageService` – Single facade to Room (`LocationSample`, `ExportedDay`).
  - `LocationSample`, `ExportedDay` – Primary entities for location logs and export status.
  - `SettingsRepository` – Sampling/DR interval settings.
- **`:dataselector`**
  - `SelectorCondition`, `SelectedSlot`, `SortOrder` – Pickup/query domain model.
  - `LocationSampleSource` – Abstraction over the source of `LocationSample`.
  - `SelectorRepository`, `BuildSelectedSlots` – Core selection logic and a small use‑case wrapper.
  - `SelectorPrefs` – DataStore facade for persisting selection conditions.
- **`:datamanager`**
  - `GeoJsonExporter` – Export `LocationSample` to GeoJSON + ZIP.
  - `GoogleDriveTokenProvider` – Abstraction for Drive access tokens.
  - `DriveTokenProviderRegistry` – Registry for background token provider.
  - `Uploader`, `UploaderFactory` – High‑level Drive upload entry points.
  - `DriveApiClient`, `DriveFolderId`, `UploadResult` – Drive REST helpers and result types.
  - `MidnightExportWorker`, `MidnightExportScheduler` – Daily export pipeline.
- **`:deadreckoning`**
  - `DeadReckoning`, `GpsFix`, `PredictedPoint` – DR engine API used by `GeoLocationService`.
- **`:auth-credentialmanager` / `:auth-appauth` (optional)**
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider` – Reference implementations of `GoogleDriveTokenProvider`.

All other types (DAO, Room database, `*Repository` implementations, low‑level HTTP helpers, etc.) are considered **internal details** and may change without notice.

### Minimal integration example

The following snippets show a minimal, end‑to‑end integration in a host app.

#### 1. Start the location service and observe history

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

#### 2. Run Pickup (selection) on stored samples

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

#### 3. Configure Drive upload and daily export

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

## Quick Start

> The source under `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning`, and `auth-*` is already included and wired in this repository.  
> You only need to prepare local configuration files and (optionally) Google Drive credentials to build and run the sample app.

### 0. Requirements

- Android Studio with AGP 8.11+ support (or the bundled `./gradlew`)
- JDK 17 (Gradle and Kotlin JVM target 17)
- Android SDK with API level 26+ installed (project uses `compileSdk = 36`)

### 1. Prepare `local.properties`

`local.properties` stores **developer machine‑specific settings** such as the Android SDK path (normally excluded from Git).  
Android Studio usually generates this file automatically; if not, create it at the project root.

**Example: local.properties (local environment, do not commit)**
```properties
sdk.dir=C:\\Android\\Sdk          # Example for Windows
# sdk.dir=/Users/you/Library/Android/sdk   # Example for macOS
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

### 2. Prepare `secrets.properties`

`secrets.properties` stores **authentication‑related and other sensitive values** used by the `secrets-gradle-plugin`.  
The repository includes `local.default.properties` as a template; copy it and replace the values as needed:

```bash
cp local.default.properties secrets.properties   # or copy manually on Windows
```

**Example: local.default.properties (template, committed)**
```properties
# Credential Manager (server) client ID for Google Sign-In / Identity.
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com

# AppAuth (installed app) client ID for OAuth2 with custom scheme redirect.
# Use an "installed app" client, not the server client ID above.
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com
```

**Example: secrets.properties (local file, do not commit)**
```properties
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com

# Optional: Google Maps API key used by the sample app manifest.
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

> For simple local builds, dummy values are sufficient.  
> To actually sign in and upload to Drive or call Maps, configure real client IDs / API keys in `secrets.properties`.

### 3. Prepare Google Drive Integration

This library supports **multiple authentication implementations**. Choose the one that best fits your needs:

#### Option 1: AppAuth (Recommended for most cases)
**Standards-compliant OAuth 2.0 with PKCE**

1) Add dependency to your app module:
```gradle
implementation(project(":auth-appauth"))
```

2) Create OAuth 2.0 credentials in Google Cloud Console:
   - Create a project
   - Configure OAuth consent screen
   - Create an **Installed app** client (for example "Android" or "iOS"), **not** a Web application client
   - Add an authorized redirect URI that uses a custom scheme, e.g. `com.yourapp:/oauth2redirect`

3) Initialize the token provider:
```kotlin
val tokenProvider = AppAuthTokenProvider(
    context = applicationContext,
    clientId = "YOUR_CLIENT_ID.apps.googleusercontent.com",
    redirectUri = "com.yourapp:/oauth2redirect"
)

// Start authorization
val intent = tokenProvider.buildAuthorizationIntent()
startActivityForResult(intent, REQUEST_CODE)

// Handle response
tokenProvider.handleAuthorizationResponse(data)

// Use with uploader
val uploader = KotlinDriveUploader(context, tokenProvider)
```

**Advantages:**
- ✅ RFC 8252 compliant (OAuth 2.0 for native apps)
- ✅ PKCE support for enhanced security
- ✅ Works with any OAuth 2.0 provider
- ✅ Long-term maintenance guaranteed

#### Option 2: Credential Manager (Recommended for Android 14+)
**Modern Android authentication (2025 standard)**

1) Add dependency to your app module:
```gradle
implementation(project(":auth-credentialmanager"))
```

2) Create OAuth 2.0 credentials in Google Cloud Console:
   - Create **Web application** credentials
   - Note the client ID (server client ID)

3) Initialize the token provider:
```kotlin
val tokenProvider = CredentialManagerTokenProvider(
    context = applicationContext,
    serverClientId = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
)

// Sign in
val credential = tokenProvider.signIn()

// Get token
val token = tokenProvider.getAccessToken()
```

**Advantages:**
- ✅ Google's 2025 recommended approach
- ✅ Best integration with Android system
- ✅ Separation of authentication and authorization

**Note:** This implementation is currently a reference. Full integration with AuthorizationClient API is in progress.  
In the bundled sample app, the `serverClientId` is read from `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID`, which is generated by the `secrets-gradle-plugin` from the `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` key in `secrets.properties`.

#### Option 3: Legacy (Deprecated, backward compatibility only)
**Uses deprecated GoogleAuthUtil - Not recommended for new code**

The existing `GoogleAuthRepository` is still available but marked as deprecated. It will continue to work but should not be used in new projects.

```kotlin
@Deprecated("Use AppAuth or Credential Manager instead")
val tokenProvider = GoogleAuthRepository(context)
```

#### Required OAuth Scopes
All implementations require these Google Drive scopes:
- `https://www.googleapis.com/auth/drive.file` (access to files created/opened by the app)
- `https://www.googleapis.com/auth/drive.metadata.readonly` (folder ID validation, etc.)

#### Custom Implementation
You can also implement your own `GoogleDriveTokenProvider`:

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

### 4. Add Permissions (for confirmation)

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

- Follows Kotlin coding conventions
- UI implemented with Jetpack Compose
- Data persistence with Room
- Background tasks with WorkManager
- Unified formatting via `Formatters.kt`

---

## Feature Implementation Status

| Feature                    | Status          | Notes                                             |
|----------------------------|-----------------|---------------------------------------------------|
| Location recording (Room)  | [v] Implemented | Saved in Room DB                                  |
| Daily export (GeoJSON+ZIP) | [v] Implemented | Executed at midnight by MidnightExportWorker      |
| Google Drive upload        | [v] Implemented | Uses Kotlin‑based uploader                        |
| Pickup (interval/count)    | [v] Implemented | Works with ViewModel + UseCase and UI integration |
| Drive full‑scope auth      | [-] In progress | Full‑scope file browsing/updating not implemented |
| UI: DriveSettingsScreen    | [v] Implemented | Auth, folder settings, and test functions         |
| UI: PickupScreen           | [v] Implemented | Input conditions and display of extracted results |
| UI: History list           | [v] Implemented | Chronological view of saved samples               |
