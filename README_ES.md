# GeoLocationProvider (versión en español)

GeoLocationProvider es un SDK y una aplicación de ejemplo para **registrar, almacenar, exportar y subir datos de localización** en Android.  
Registra localización en segundo plano en una base de datos Room, exporta los datos como GeoJSON+ZIP y puede subirlos a **Google Drive**.

---

## Funcionalidades

- Obtención de localización en segundo plano (intervalo de muestreo configurable)
- Almacenamiento en base de datos Room (`LocationSample`, `ExportedDay`)
- Exportación a formato GeoJSON con compresión ZIP
- Exportación diaria automática a medianoche (`MidnightExportWorker`)
- Exportación manual para copia de seguridad de ayer y vista previa de hoy
- Función Pickup (muestras representativas por período / número de puntos)
- Subida a Google Drive con selección de carpeta

---

## Arquitectura

### Estructura de módulos

El proyecto está organizado como un proyecto Gradle multimódulo:

- **`:app`** – Aplicación de ejemplo basada en Jetpack Compose (lista de historial, pantalla de Pickup, ajustes de Drive, exportación manual).
- **`:core`** – Servicio de localización (`GeoLocationService`), lógica de sensores y aplicación de intervalos de muestreo. Persiste en `:storageservice` y usa `:deadreckoning`.
- **`:storageservice`** – `AppDatabase` de Room, DAOs y fachada `StorageService`. Puerta única hacia la base de datos para registros de localización y estado de exportación.
- **`:dataselector`** – Lógica de selección para Pickup. Filtra `LocationSample` y construye filas representativas (`SelectedSlot`).
- **`:datamanager`** – Exportación a GeoJSON, compresión ZIP, `MidnightExportWorker` / `MidnightExportScheduler` e integración con Google Drive.
- **`:deadreckoning`** – Motor y API de Dead Reckoning (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`).
- **`:auth-appauth`** – Implementación de `GoogleDriveTokenProvider` basada en AppAuth.
- **`:auth-credentialmanager`** – Implementación de `GoogleDriveTokenProvider` basada en Credential Manager + Identity.

Dirección aproximada de dependencias:

- `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, módulos de autenticación  
- `:core` → `:storageservice`, `:deadreckoning`  
- `:datamanager` → `:storageservice`, integración con Drive  
- `:dataselector` → solo la abstracción `LocationSampleSource` (la implementación concreta está en `:app`, envolviendo `StorageService`)

### Componentes principales

- **Entidades: `LocationSample` / `ExportedDay`**  
  `LocationSample` almacena latitud, longitud, marca de tiempo, velocidad, nivel de batería, etc.  
  `ExportedDay` gestiona el estado de exportación por día (exportado localmente, resultado de subida, último error).

- **`StorageService`**  
  Fachada única alrededor de Room (`AppDatabase` / DAOs).  
  El resto de módulos acceden a la base de datos solo a través de `StorageService`, nunca directamente a los DAOs.

- **Pickup (`:dataselector`)**  
  `SelectorCondition`, `SelectorRepository`, `BuildSelectedSlots` y `SelectorPrefs` implementan la funcionalidad de Pickup.  
  Afinan y ajustan las muestras a una rejilla temporal y representan los huecos como `SelectedSlot(sample = null)`.  
  `LocationSampleSource` abstrae el origen de las muestras; la app de ejemplo implementa esta interfaz usando `StorageService`.

- **Dead Reckoning (`:deadreckoning`)**  
  Proporciona un API público (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`) y un motor interno (`DeadReckoningEngine`, `DeadReckoningImpl`, etc.).  
  `GeoLocationService` crea instancias de DR mediante `DeadReckoningFactory.create(applicationContext)` y las controla con `start()` / `stop()`. Los fixes de GPS se tratan como anclas fuertes: cada fix restablece la posición interna de DR a la lat/lon de GPS y mezcla la velocidad con `velocityGain`. El motor aplica un límite físico de velocidad por paso (`maxStepSpeedMps`) para descartar picos de IMU y expone `isLikelyStatic()` para una detección simple de estado estático (estilo ZUPT). Antes del primer fix de GPS, `predict()` puede devolver una lista vacía porque la posición absoluta aún no está inicializada.

- **Exportación diaria (`MidnightExportWorker` / `MidnightExportScheduler`)**  
  Pipeline basado en WorkManager que:
  - Recorre los días hasta “ayer” usando la zona horaria `Asia/Tokyo`
  - Obtiene `LocationSample` por día con `StorageService.getLocationsBetween`
  - Exporta cada día a GeoJSON+ZIP mediante `GeoJsonExporter.exportToDownloads`
  - Marca exportación local con `markExportedLocal`
  - Opcionalmente sube a Drive usando `UploaderFactory` y `GoogleDriveTokenProvider`
  - Registra el resultado con `markUploaded` / `markExportError`
  - Borra los ZIP en todos los casos y borra registros antiguos tras una subida exitosa

- **Integración con Drive (`GoogleDriveTokenProvider` y relacionados)**  
  `GoogleDriveTokenProvider` abstrae la obtención de tokens de acceso a Drive.  
  `DriveTokenProviderRegistry` proporciona un proveedor de tokens para tareas en segundo plano.  
  `Uploader` / `UploaderFactory` (respaldados por `KotlinDriveUploader`) realizan las subidas usando `DriveApiClient`.  
  `GoogleAuthRepository` (basado en GoogleAuthUtil) se mantiene solo por compatibilidad y está desaconsejado en código nuevo.

- **UI (Compose) – `MainActivity`, `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen`**  
  La aplicación usa una estructura `NavHost` de dos niveles:
  - A nivel de Activity: destinos `"home"` y `"drive_settings"` (desde el menú de Drive en la AppBar).
  - A nivel de app: destinos `"home"` y `"pickup"` (botón Pickup de la AppBar).  
  Los permisos se gestionan mediante `ActivityResultContracts.RequestMultiplePermissions`, y el interruptor Start/Stop está encapsulado en `ServiceToggleAction`. La pantalla de mapa (pestaña `Map` en `GeoLocationProviderScreen`) usa MapConductor con backend de Google Maps y visualiza las filas recientes de `LocationSample` como polilíneas: DeadReckoning como polilínea roja y más fina dibujada delante, y GPS como polilínea azul y más gruesa dibujada detrás. Los checkboxes para `GPS` y `DeadReckoning`, el campo `Count (1-1000)` y el botón `Apply` / `Cancel` controlan qué proveedores se muestran y cuántos puntos se tienen en cuenta; la superposición de depuración en la esquina superior derecha muestra los contadores `GPS`, `DR` y `ALL` como `mostrados / total en BD`.

---

## Visión general de la API pública

GeoLocationProvider está pensado tanto como:

- una **aplicación de ejemplo** (`:app`) que puedes ejecutar tal cual, y
- un conjunto de **módulos reutilizables** que puedes integrar en tu propia app.

Al consumir las librerías, normalmente usarás los siguientes módulos y tipos:

### Módulos y puntos de entrada principales

- **`:core`**
  - `GeoLocationService` – Servicio en primer plano que registra `LocationSample` en la base de datos.
  - `UploadEngine` – Enum que indica el motor de subida.

- **`:storageservice`**
  - `StorageService` – Fachada única sobre Room (`LocationSample`, `ExportedDay`).
  - `LocationSample`, `ExportedDay` – Entidades principales para los registros de localización y el estado de exportación.
  - `SettingsRepository` – Configuración de intervalos de muestreo / Dead Reckoning.

- **`:dataselector`**
  - `SelectorCondition`, `SelectedSlot`, `SortOrder` – Modelo de dominio para Pickup / consultas.
  - `LocationSampleSource` – Abstracción sobre el origen de `LocationSample`.
  - `SelectorRepository`, `BuildSelectedSlots` – Lógica de selección y caso de uso de alto nivel.
  - `SelectorPrefs` – Fachada DataStore para persistir condiciones de selección.

- **`:datamanager`**
  - `GeoJsonExporter` – Exporta `LocationSample` a GeoJSON + ZIP.
  - `GoogleDriveTokenProvider` – Abstracción para obtener tokens de acceso a Drive.
  - `DriveTokenProviderRegistry` – Registro de proveedores de tokens para segundo plano.
  - `Uploader`, `UploaderFactory` – Puntos de entrada de alto nivel para subidas a Drive.
  - `DriveApiClient`, `DriveFolderId`, `UploadResult` – Ayudas para llamadas REST y modelos de resultado.
  - `MidnightExportWorker`, `MidnightExportScheduler` – Pipeline de exportación diaria.

- **`:deadreckoning`**
  - `DeadReckoning`, `GpsFix`, `PredictedPoint` – API del motor DR usado desde `GeoLocationService`.
  - `DeadReckoningConfig`, `DeadReckoningFactory` – Configuración y factoría para crear instancias de DR.

- **`:auth-credentialmanager` / `:auth-appauth`** (opcional)
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider` – Implementaciones de referencia de `GoogleDriveTokenProvider`.

Los demás tipos (DAOs y `AppDatabase`, implementaciones de repositorio, helpers HTTP de bajo nivel, etc.) se consideran **detalles internos** y pueden cambiar sin previo aviso.

---

## Ejemplos mínimos de integración

Los siguientes fragmentos ilustran una integración extremo a extremo en tu propia app.

### 1. Arrancar el servicio de localización y observar el historial

```kotlin
// En tu Activity
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
// En un ViewModel
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    val latest: StateFlow<List<LocationSample>> =
        StorageService.latestFlow(app.applicationContext, limit = 100)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
```

### 2. Ejecutar Pickup (selección) sobre muestras almacenadas

```kotlin
// Implementación de LocationSampleSource basada en StorageService
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
        intervalSec = 60,         // Rejilla de 60 segundos
        limit = 1000,
        order = SortOrder.OldestFirst
    )
    return useCase(cond)
}
```

### 3. Configurar subida a Drive y exportación diaria

```kotlin
// En tu clase Application
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Ejemplo: reutilizar CredentialManagerTokenProvider desde :auth-credentialmanager
        val provider = CredentialManagerTokenProvider(
            context = this,
            serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
        )

        // Registrar proveedor de segundo plano para MidnightExportWorker
        DriveTokenProviderRegistry.registerBackgroundProvider(provider)

        // Programar exportación diaria a medianoche
        MidnightExportScheduler.scheduleNext(this)
    }
}
```

```kotlin
// Subir un archivo a Drive usando UploaderFactory (opcional)
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

## Opciones de autenticación con Google Drive

Todas las subidas a Drive pasan por `GoogleDriveTokenProvider`. El contrato básico es:

- Los tokens devueltos son **tokens de acceso “puros”**, sin el prefijo `"Bearer "`.
- Los fallos normales (errores de red, usuario no autenticado, falta de consentimiento, etc.) deben indicarse devolviendo `null`, no lanzando excepciones.
- Los proveedores no deben lanzar UI directamente desde contextos en segundo plano; si se requiere un flujo de UI, devuelve `null` y deja la decisión a la app.

Puedes elegir alguna de las siguientes implementaciones o escribir la tuya propia.

### Opción 1: AppAuth (OAuth 2.0 estándar con PKCE)

Esta opción usa [AppAuth for Android](https://github.com/openid/AppAuth-Android) para realizar OAuth 2.0 para apps nativas.

1) Añade la dependencia en tu módulo de app:

```gradle
implementation(project(":auth-appauth"))
```

2) Crea credenciales OAuth 2.0 en Google Cloud Console:

- Configura la pantalla de consentimiento OAuth.
- Crea un client ID como **Aplicación instalada** (Android / otras apps instaladas), **no** como Aplicación web.
- Permite un esquema de URI personalizado, por ejemplo: `com.mapconductor.plugin.provider.geolocation`.
- Registra una URI de redirección, por ejemplo:

```text
com.mapconductor.plugin.provider.geolocation:/oauth2redirect
```

3) Inicializa el proveedor de tokens (ejemplo):

```kotlin
val tokenProvider = AppAuthTokenProvider(
    context = applicationContext,
    clientId = BuildConfig.APPAUTH_CLIENT_ID,
    redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
)

// Inicia el flujo de autorización desde una Activity
val intent = tokenProvider.buildAuthorizationIntent()
startActivityForResult(intent, REQUEST_CODE)

// Maneja la respuesta
tokenProvider.handleAuthorizationResponse(data)

// Úsalo con el uploader
val uploader = KotlinDriveUploader(context, tokenProvider)
```

En el proyecto de ejemplo, `APPAUTH_CLIENT_ID` se proporciona mediante `secrets.properties` y `secrets-gradle-plugin`.

### Opción 2: Credential Manager (autenticación moderna en Android, usada por la app de ejemplo)

Esta opción utiliza Android Credential Manager + Identity. Es la ruta por defecto de la app de ejemplo y se integra bien con Android 14+.

1) Añade la dependencia en tu módulo de app:

```gradle
implementation(project(":auth-credentialmanager"))
```

2) Crea credenciales OAuth 2.0 en Google Cloud Console:

- Crea credenciales como **Aplicación web**.
- Usa el client ID resultante como **server client ID**.

3) Inicializa el proveedor de tokens:

```kotlin
val tokenProvider = CredentialManagerTokenProvider(
    context = applicationContext,
    serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
)

// Inicio de sesión (desde Activity / UI)
val credential = tokenProvider.signIn()

// Obtención del token (también desde segundo plano)
val token = tokenProvider.getAccessToken()
```

En la app de ejemplo:

- `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` se lee de `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID`.
- Ese valor se genera mediante `secrets-gradle-plugin` a partir de la clave `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` en `secrets.properties`.
- `App` registra `CredentialManagerAuth` como proveedor de segundo plano a través de `DriveTokenProviderRegistry`.

### Opción 3: Legacy (solo compatibilidad hacia atrás)

`GoogleAuthRepository` (basado en GoogleAuthUtil) sigue disponible pero está **deprecado** y no debe usarse en proyectos nuevos:

```kotlin
@Deprecated("Use AppAuth or Credential Manager instead")
val tokenProvider = GoogleAuthRepository(context)
```

### Scopes OAuth necesarios

Todas las implementaciones usan los siguientes scopes de Google Drive:

- `https://www.googleapis.com/auth/drive.file` – Acceso a archivos creados / abiertos por la app.
- `https://www.googleapis.com/auth/drive.metadata.readonly` – Validación de IDs de carpeta y metadatos.

### Implementación personalizada de `GoogleDriveTokenProvider`

```kotlin
class MyCustomTokenProvider : GoogleDriveTokenProvider {
    override suspend fun getAccessToken(): String? {
        // Implementación personalizada
    }

    override suspend fun refreshToken(): String? {
        // Implementación personalizada
    }
}
```

---

## Inicio rápido

> Los módulos `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning` y `auth-*` ya están conectados en este repositorio.  
> Solo necesitas preparar los archivos de configuración locales y (opcionalmente) las credenciales de Google Drive para compilar y ejecutar la app de ejemplo.

### 0. Requisitos

- Android Studio con soporte para AGP 8.1+ (o el `./gradlew` incluido)
- JDK 17 (Gradle y Kotlin usan objetivo JVM 17)
- SDK de Android con API 26+ instalada (el proyecto usa `compileSdk = 36`)

### 1. Preparar `local.properties`

`local.properties` almacena **ajustes específicos de la máquina del desarrollador** (principalmente la ruta al SDK de Android) y normalmente no se versiona.  
Android Studio suele generarlo automáticamente; si no existe, créalo en la raíz del proyecto.

**Ejemplo: `local.properties` (entorno local, NO commitear)**

```properties
sdk.dir=C:\\Android\\Sdk          # Ejemplo en Windows
# sdk.dir=/Users/you/Library/Android/sdk   # Ejemplo en macOS
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

### 2. Preparar `secrets.properties`

`secrets.properties` almacena **valores sensibles relacionados con autenticación y APIs**, que se inyectan en BuildConfig mediante `secrets-gradle-plugin`.  
El repositorio incluye `local.default.properties` como plantilla; la idea es copiarlo y editarlo:

```bash
cp local.default.properties secrets.properties   # En Windows puedes copiarlo con el Explorador
```

**Ejemplo: `local.default.properties` (plantilla, se puede commitear)**

```properties
# Credential Manager (server) client ID for Google Sign-In / Identity.
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com

# AppAuth (installed app) client ID for OAuth2 with custom scheme redirect.
# Use an "installed app" client, not the server client ID above.
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com
```

**Ejemplo: `secrets.properties` (entorno local, NO commitear)**

```properties
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com

# Clave de Google Maps usada en el Manifest de la app de ejemplo (opcional)
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

Para compilar localmente basta con usar valores ficticios.  
Si quieres iniciar sesión realmente en Drive o usar los mapas, deberás configurar client IDs / claves reales obtenidas en Cloud Console.

### 3. Preparar la integración con Google Drive

Para usar la subida a Drive, configura OAuth 2.0 en Google Cloud Console:

1. Crear un proyecto y configurar la pantalla de consentimiento OAuth.  
2. Crear client IDs OAuth:
   - Un client ID de **Aplicación web** para Credential Manager (`CREDENTIAL_MANAGER_SERVER_CLIENT_ID`).
   - Un client ID de **Aplicación instalada** para AppAuth (`APPAUTH_CLIENT_ID`) con el esquema / URI de redirección personalizados.  
3. Habilitar la API de Drive y conceder los scopes:
   - `https://www.googleapis.com/auth/drive.file`
   - `https://www.googleapis.com/auth/drive.metadata.readonly`
4. Colocar los client IDs en `secrets.properties` como se muestra arriba.

### 4. Permisos (para confirmar)

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />

<!-- Opcional: solo si necesitas localización cuando no hay servicio en primer plano ni UI visible -->
<!-- <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" /> -->
```

### 5. Build

```bash
./gradlew :app:assembleDebug
```

---

## Desarrollo

- Sigue las convenciones de código de Kotlin.
- UI implementada con Jetpack Compose.
- Persistencia con Room + DataStore (para ajustes).
- Tareas en segundo plano con WorkManager (`MidnightExportWorker`, `MidnightExportScheduler`).
- Formateo y lógica de presentación común centralizados en utilidades como `Formatters.kt`.

### Codificación de fuentes y comentarios

- Todo el código de producción de este repositorio (Kotlin / Java / XML / scripts Gradle, etc.) está escrito usando **solo caracteres ASCII**.  
  No se usan caracteres multibyte en código, comentarios ni literales de cadena, para evitar problemas de codificación entre herramientas y plataformas.
- La documentación multilingüe (japonés / español) se ofrece por separado en `README_JA.md`, `README_ES.md` y otros archivos `*.md`.
- El estilo de comentarios se ha unificado entre módulos:
  - APIs públicas y clases principales: KDoc (`/** ... */`) describiendo rol, política de diseño, uso y contrato.
  - Detalles internos de implementación: comentarios de una línea `// ...` y encabezados de sección simples como `// ---- Section ----`.
  - Se evitan banners decorativos o estilos de separador inconsistentes entre módulos.

---

## Estado de implementación de funcionalidades

| Funcionalidad                   | Estado           | Notas                                             |
|---------------------------------|------------------|---------------------------------------------------|
| Registro de localización (Room) | [v] Implementado | Guardado en DB Room                               |
| Exportación diaria (GeoJSON+ZIP)| [v] Implementado | Ejecutado a medianoche por `MidnightExportWorker` |
| Subida a Google Drive           | [v] Implementado | Usa el cargador basado en Kotlin                  |
| Pickup (intervalo / número)     | [v] Implementado | Integrado con ViewModel + UseCase + UI            |
| Autenticación Drive full-scope  | [-] En progreso  | Navegación/actualización de archivos existentes   |
| UI: DriveSettingsScreen         | [v] Implementado | Ajustes de autenticación, carpeta y pruebas       |
| UI: PickupScreen                | [v] Implementado | Entrada de condiciones y lista de resultados      |
| UI: lista de historial          | [v] Implementado | Visualización cronológica de muestras guardadas   |
