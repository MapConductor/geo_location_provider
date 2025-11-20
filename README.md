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
```

> Note: These keys are not required. Add them only if necessary.

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
   - Create **Web application** credentials (not Android)
   - Add authorized redirect URI: `com.yourapp:/oauth2redirect`

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
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
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
