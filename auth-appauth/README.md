# :auth-appauth (Google Drive Token Provider via AppAuth)

Gradle module: `:auth-appauth`

This module provides an AppAuth-based implementation of `GoogleDriveTokenProvider` for Google Drive uploads.

## Module boundary contract

What you write:

- Run the interactive OAuth flow from a foreground Activity (AppAuth).
- Persist `AuthState` so background workers can refresh tokens without UI.

What you get:

- A `GoogleDriveTokenProvider` that can return access tokens for Drive REST calls.
- Background upload code (`:datamanager`) can use it via `DriveTokenProviderRegistry`.

## What you get

- `AppAuthTokenProvider`: implements `GoogleDriveTokenProvider` using OAuth 2.0 Authorization Code + PKCE via AppAuth.

File:

- `auth-appauth/src/main/java/com/mapconductor/plugin/provider/geolocation/auth/appauth/AppAuthTokenProvider.kt`

## How to use

1. Add the module dependency:

```kotlin
dependencies {
  implementation(project(":auth-appauth"))
}
```

2. Create a provider:

```kotlin
val provider = AppAuthTokenProvider(
  context = appContext,
  clientId = BuildConfig.APPAUTH_CLIENT_ID,
  // Use your app's redirect URI. The sample app uses:
  // "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
  redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
)
```

3. Start the auth flow from a foreground Activity:

```kotlin
startActivity(provider.buildAuthorizationIntent())
```

4. Handle the redirect result and persist state:

- Call `handleAuthorizationResponse(intent)` from the Activity that receives the redirect intent.

5. Register it for background uploads:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

## Detailed end-to-end wiring (what you write -> what you get)

### 1) Provide a redirect Activity (redirect URI -> callback delivery)

What you write:

- An Activity (or intent handler) that receives the custom-scheme redirect URI.

Manifest sketch (match your redirect URI scheme/path):

```xml
<activity
  android:name=".auth.AppAuthSignInActivity"
  android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data
      android:scheme="com.mapconductor.plugin.provider.geolocation"
      android:path="/oauth2redirect" />
  </intent-filter>
</activity>
```

What you get:

- The browser/AppAuth flow can return to your app with an `Intent` that contains the authorization response.

### 2) Persist `AuthState` (interactive -> background refresh)

What you write:

- In the redirect Activity, call the provider's handler (it exchanges code -> tokens and persists state).

```kotlin
val ok = provider.handleAuthorizationResponse(intent)
```

What you get:

- After a successful flow, `provider.getAccessToken()` can return tokens in background (AppAuth can refresh).

### 3) Register as background provider (datamanager -> auth)

What you write:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

What you get:

- Workers/managers in `:datamanager` can upload to Drive without launching UI.

## Important notes

- This module does not declare an Activity or intent filter for the redirect URI. The host app must provide one.
- `GoogleDriveTokenProvider` must not start UI from background contexts. If tokens cannot be obtained without UI, implementations must return `null`.
