# :auth-credentialmanager (Proveedor de token vía Credential Manager)

Módulo Gradle: `:auth-credentialmanager`

Este módulo provee una implementación de `GoogleDriveTokenProvider` usando Android Credential Manager (sign-in) y Google Identity AuthorizationClient (scopes Drive + access tokens).

## Contrato de frontera

La app host hace:

- `signIn(...)` desde una Activity (interactivo).
- Si faltan scopes, maneja el flujo interactivo de autorización.

Obtienes:

- `getAccessToken()` devuelve token sin UI solo cuando los scopes ya están otorgados.
- Si se requiere interacción, devuelve `null` (background lo trata como "no autorizado").

## Uso básico

```kotlin
val provider = CredentialManagerTokenProvider(
  context = activity,
  serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
)
val credential = provider.signIn(activity)
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

## Flujo end-to-end (detallado: llamadas -> efectos)

Este módulo está pensado para que el código en background nunca tenga que iniciar UI.

### Paso 1: Sign-in (elegir cuenta)

```kotlin
val idCredential = provider.signIn(activity)
```

- Si el usuario cancela, devuelve `null` y conviene mantener uploads deshabilitados.

### Paso 2: Otorgar scopes de Drive (interactivo cuando haga falta)

- Intenta `provider.getAccessToken()` una vez.
- Si retorna `null`, probablemente faltan scopes. La app host debe completar un flujo interactivo de autorización (AuthorizationClient con resolution) y luego reintentar.

### Paso 3: Registrar para uploads en background

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

- Workers/managers de `:datamanager` pueden pedir tokens en background.
- Si se requiere interacción, retorna `null` y el upload se omite de forma segura.

## Notas

- Background no debe iniciar UI; devuelve `null` si se requiere interacción.
- Usa un client ID tipo "web/server" para Credential Manager.
