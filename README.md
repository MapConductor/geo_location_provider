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

> This repository includes four modules: `app`, `core`, `dataselector`, and `datamanager`.  
> Users do not need to add or configure additional modules manually.

- **:core**  
  Provides location acquisition, Room entities/DAOs/database handling, and utilities.

- **:dataselector**  
  Provides data extraction logic based on conditions, such as the Pickup feature.

- **:datamanager**  
  Provides data export, Google Drive integration, and WorkManager tasks.

- **:app**  
  Compose-based UI (history list, Pickup screen, Drive settings screen, etc.).

### Key Components

- **LocationSample / ExportedDay Entities**  
  The core data models for collected location samples. `LocationSample` stores latitude, longitude, timestamp, battery level, etc., and `ExportedDay` records which dates have already been exported to prevent duplication.

- **PickupViewModel & BuildSelectRows Use Case**  
  Core of the Pickup feature for thinning and extracting data. It selects representative samples based on specified intervals or counts, and prepares them for UI display.

- **MidnightExportWorker & MidnightExportScheduler**  
  Background tasks using WorkManager. Every day at midnight, they export the previous day’s data to GeoJSON + ZIP and upload it to Google Drive. The scheduler handles booking the next execution.

- **GoogleDriveTokenProvider, KotlinDriveUploader & DriveApiClient**  
  Core building blocks for Google Drive integration. `GoogleDriveTokenProvider` is the abstraction for obtaining OAuth access tokens, `KotlinDriveUploader` performs the actual uploads to Drive, and `DriveApiClient` is used for REST calls such as folder validation.  
  A legacy `GoogleAuthRepository` implementation (using deprecated GoogleAuthUtil) is still available for backward compatibility but should not be used in new code.

- **UI Screens (MainActivity, PickupScreen, DriveSettingsScreen, etc.)**  
  User interface implemented with Jetpack Compose.
    - `MainActivity`: Entry point for starting/stopping the service and showing history
    - `PickupScreen`: Extracts and displays samples based on user‑specified conditions
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

> The files under `app`, `core`, `dataselector`, and `datamanager` are already included in the repository and do not require setup. Only the preparation of local‑specific files and Google Drive integration is described below.

### 1. Prepare `local.default.properties` / `local.properties`

`local.properties` stores **developer machine‑specific settings** (normally excluded from Git). This project assumes `local.default.properties` is provided as a template.

- Recommended flow:
    1) Place `local.default.properties` at the root (template)
    2) Manually create or copy `local.properties` during initial setup
    3) Edit values as needed

**Example: local.default.properties (template, can be committed)**
```properties
# Path to Android SDK. Can be empty or dummy.
sdk.dir=/path/to/Android/sdk

# Optional: Gradle JVM options
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
```

**Example: local.properties (local environment, do not commit)**
```properties
sdk.dir=C:\Android\Sdk          # Example for Windows
# sdk.dir=/Users/you/Library/Android/sdk   # Example for macOS
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

> Note: Android Studio may auto‑generate `local.properties`. If so, just edit it.

### 2. Prepare `secrets.properties`

`secrets.properties` stores **authentication and sensitive default values** (excluded from Git). While some settings can be configured in the UI, this file is useful for CI or setting defaults in development.

**Example: secrets.properties (do not commit)**
```properties
# Optional: Default upload folder ID (can be overridden via UI)
DRIVE_DEFAULT_FOLDER_ID=

# Optional: Default upload engine (set if using other than kotlin)
UPLOAD_ENGINE=kotlin

# Optional: Enable full‑scope Drive access (not implemented yet)
DRIVE_FULL_SCOPE=false

# Required when using the sample app's Credential Manager-based auth.
# Exposed as BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
# via the secrets-gradle-plugin.
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
```

> Note: These keys are not required for the core library itself.  
> If you want to use the bundled Credential Manager sample screen, you must set a valid `CREDENTIAL_MANAGER_SERVER_CLIENT_ID`.

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
./gradlew assembleDebug
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
