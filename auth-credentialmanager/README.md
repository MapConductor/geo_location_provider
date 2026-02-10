# :auth-credentialmanager (Google Drive Token Provider via Credential Manager)

Gradle module: `:auth-credentialmanager`

This module provides a `GoogleDriveTokenProvider` implementation using Android Credential Manager (sign-in) and Google Identity AuthorizationClient (Drive scope authorization + access tokens).

## Module boundary contract

What you write:

- Call `signIn(...)` from an Activity (interactive).
- Ensure the app requests/obtains the required Drive scopes interactively when needed.

What you get:

- `getAccessToken()` returns a Drive access token without UI only when the scopes are already granted.
- Background upload code can treat `null` as "not authorized yet" and surface UI prompts in the foreground.

## What you get

- `CredentialManagerTokenProvider`: implements `GoogleDriveTokenProvider`
  - `signIn(...)` must be called from an Activity (interactive)
  - `getAccessToken()` returns a token only when scopes are already granted; otherwise it returns `null` and the app must run an interactive authorization flow

File:

- `auth-credentialmanager/src/main/java/com/mapconductor/plugin/provider/geolocation/auth/credentialmanager/CredentialManagerTokenProvider.kt`

## How to use

1. Add the module dependency:

```kotlin
dependencies {
  implementation(project(":auth-credentialmanager"))
}
```

2. Create a provider:

```kotlin
val provider = CredentialManagerTokenProvider(
  context = activity, // Activity context recommended
  serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
)
```

3. Sign in (UI):

```kotlin
val credential = provider.signIn(activity)
```

4. Register it for background uploads:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

## Detailed end-to-end flow (what you call -> what you get)

This module is designed so that background upload code never needs to start UI.

### Step 1: Sign in (choose an account)

What you write:

```kotlin
val idCredential = provider.signIn(activity)
```

What you get:

- A signed-in Google account (via Credential Manager).
- If the user cancels or no credential is available, you get `null` and should keep uploads disabled.

### Step 2: Grant Drive scopes (interactive, when needed)

What you write:

- Try to get a token once. If it returns `null`, scopes are likely not granted yet and the app should run an interactive authorization flow (using Google Identity AuthorizationClient) and then retry.

What you get:

- After scopes are granted, `provider.getAccessToken()` can return a token without UI, which enables background uploads.

### Step 3: Register for background uploads

What you write:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

What you get:

- `:datamanager` Workers and managers can call `getAccessToken()` in background.
- If the token cannot be obtained without interaction, the provider returns `null` and uploads are skipped safely.

## Important notes

- Background code must never start UI. If authorization requires user interaction, `getAccessToken()` returns `null` by design.
- Use a "web/server" client ID for Credential Manager (`serverClientId`).
