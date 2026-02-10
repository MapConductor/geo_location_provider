# :auth-appauth (Proveedor de token para Drive vía AppAuth)

Módulo Gradle: `:auth-appauth`

Este módulo provee una implementación de `GoogleDriveTokenProvider` basada en AppAuth para subidas a Google Drive.

## Contrato de frontera

La app host hace:

- Ejecuta OAuth interactivo desde una Activity en primer plano.
- Maneja el redirect y persiste estado.

Obtienes:

- Un `GoogleDriveTokenProvider` que entrega access tokens.
- Uso en background vía `DriveTokenProviderRegistry`.

## Uso básico

```kotlin
val provider = AppAuthTokenProvider(
  context = appContext,
  clientId = BuildConfig.APPAUTH_CLIENT_ID,
  // Usa el redirect URI de tu app. La app de ejemplo usa:
  // "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
  redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
)
startActivity(provider.buildAuthorizationIntent())
```

Luego registra:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

## Wiring end-to-end (detallado: código -> efectos)

### 1) Declarar una Activity para el redirect (redirect URI -> callback)

Ejemplo de manifest (haz match con tu scheme/path):

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

### 2) Persistir `AuthState` (interactivo -> refresh en background)

- En la Activity de redirect, llama al handler (intercambia code -> tokens y persiste estado):

```kotlin
val ok = provider.handleAuthorizationResponse(intent)
```

### 3) Registrar para uploads en background

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

- Workers/managers de `:datamanager` pueden subir a Drive sin iniciar UI.

## Notas

- El host debe declarar Activity/intent-filter para el redirect URI.
- No se debe iniciar UI desde background; si se requiere, devuelve `null`.
