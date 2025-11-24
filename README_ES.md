# GeoLocationProvider

GeoLocationProvider es un SDK y una aplicación de ejemplo para **registrar, almacenar y exportar datos de localización** en Android.  
También ofrece la posibilidad de **subir los datos exportados a Google Drive**.

---

## Funcionalidades

- Obtención de localización en segundo plano (intervalo de muestreo configurable)
- Almacenamiento mediante base de datos Room
- Exportación a formato GeoJSON (con compresión ZIP)
- Exportación automática a medianoche (Midnight Export Worker)
- Exportación manual para copia de seguridad de ayer y vista previa de hoy
- Función Pickup (extrae muestras representativas por período o número de puntos)
- Subida a Google Drive (con selección de carpeta)

---

## Arquitectura

### Estructura de módulos

> Este repositorio incluye los módulos `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning` y `auth-*`.  
> Puedes utilizar la aplicación de ejemplo tal cual o integrar únicamente los módulos que necesites en tu proyecto.

- **:core**  
  Proporciona el servicio de localización (`GeoLocationService`), la lógica relacionada con sensores y la aplicación de ajustes como el intervalo de muestreo.  
  La persistencia se delega a `:storageservice`, y el Dead Reckoning se implementa en `:deadreckoning`.

- **:storageservice**  
  Contiene `AppDatabase`, los DAOs y la fachada `StorageService`, que es la única puerta de acceso a Room.  
  Gestiona las muestras de localización (`LocationSample`) y el estado de exportación diaria (`ExportedDay`).

- **:dataselector**  
  Implementa la lógica de filtrado y selección (Pickup) en función de condiciones como período, intervalo y límite.  
  Expone `SelectorCondition`, `SelectedSlot`, `SelectorRepository`, `BuildSelectedSlots`, etc.

- **:datamanager**  
  Se encarga de la exportación a GeoJSON, compresión ZIP, `MidnightExportWorker` / `MidnightExportScheduler`  
  e integración con Google Drive (`GoogleDriveTokenProvider`, `Uploader`, `DriveTokenProviderRegistry`, etc.).

- **:deadreckoning**  
  Proporciona el motor y API de Dead Reckoning (`DeadReckoning`, `GpsFix`, `PredictedPoint`), usados desde `GeoLocationService`.

- **:auth-appauth / :auth-credentialmanager**  
  Módulos de implementación de referencia de `GoogleDriveTokenProvider` basados en AppAuth y Android Credential Manager + Identity.

- **:app**  
  Aplicación de ejemplo basada en Jetpack Compose (lista de historial, pantalla de Pickup, pantalla de ajustes de Drive, etc.).

### Componentes principales

- **Entidades LocationSample / ExportedDay**  
  `LocationSample` almacena latitud, longitud, marca de tiempo, velocidad, nivel de batería, etc.  
  `ExportedDay` registra qué días ya han sido exportados y el estado de subida a Drive (incluidos errores).

- **StorageService**  
  Fachada que actúa como única entrada a Room (`AppDatabase` / DAOs).  
  Los demás módulos acceden a la base de datos solo a través de `StorageService`; no deben importar DAOs ni `AppDatabase` directamente.

- **Pickup / dataselector**  
  `SelectorCondition`, `SelectorRepository` y `BuildSelectedSlots` forman el núcleo de la lógica de Pickup.  
  Filtran y agrupan las muestras por rejilla temporal y representan las ausencias como `SelectedSlot(sample = null)`.

- **MidnightExportWorker & MidnightExportScheduler**  
  Tareas en segundo plano basadas en WorkManager. Cada día a medianoche exportan el día anterior a GeoJSON + ZIP,  
  suben el archivo a Google Drive y actualizan el estado correspondiente en `ExportedDay`.

- **GoogleDriveTokenProvider / Uploader / DriveApiClient**  
  Componentes centrales para la integración con Google Drive.  
  `GoogleDriveTokenProvider` abstrae la obtención de tokens de acceso, `Uploader` / `UploaderFactory` se encargan de la subida de archivos  
  y `DriveApiClient` implementa llamadas REST como `/files` o `/about`.  
  La implementación heredada `GoogleAuthRepository` (basada en GoogleAuthUtil) se mantiene solo por compatibilidad y no debe usarse en código nuevo.

---

## Public API (visión general)

Este proyecto está pensado como una biblioteca reutilizable más una aplicación de ejemplo.  
Al integrarlo en tu aplicación normalmente utilizarás los siguientes módulos y tipos:

### Módulos y puntos de entrada principales

- **`:core`**
  - `GeoLocationService`  EServicio en primer plano que registra `LocationSample` en la base de datos.
  - `UploadEngine`  EEnum para configurar el tipo de exportación/subida.

- **`:storageservice`**
  - `StorageService`  EFachada única hacia Room (`LocationSample`, `ExportedDay`).
  - `LocationSample`, `ExportedDay`  EEntidades principales para los registros de localización y el estado de exportación.
  - `SettingsRepository`  EGestiona los intervalos de muestreo y de Dead Reckoning.

- **`:dataselector`**
  - `SelectorCondition`, `SelectedSlot`, `SortOrder`  EModelo de dominio de las consultas Pickup.
  - `LocationSampleSource`  EAbstracción sobre el origen de `LocationSample`.
  - `SelectorRepository`, `BuildSelectedSlots`  ELógica de selección y caso de uso para UI.
  - `SelectorPrefs`  EFachada DataStore para persistir las condiciones de selección.

- **`:datamanager`**
  - `GeoJsonExporter`  EExporta `LocationSample` a GeoJSON + ZIP.
  - `GoogleDriveTokenProvider`  EAbstracción para obtener tokens de acceso a Drive.
  - `DriveTokenProviderRegistry`  ERegistro del proveedor de tokens para tareas en segundo plano.
  - `Uploader`, `UploaderFactory`  EPuntos de entrada de alto nivel para la subida a Drive.
  - `DriveApiClient`, `DriveFolderId`, `UploadResult`  EAyudas para llamadas REST y modelos de resultado.
  - `MidnightExportWorker`, `MidnightExportScheduler`  EPipeline de exportación diaria.

- **`:deadreckoning`**
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`  EAPI del motor de Dead Reckoning, utilizada desde `GeoLocationService`.

- **`:auth-credentialmanager` / `:auth-appauth` (opcional)**
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider`  EImplementaciones de referencia de `GoogleDriveTokenProvider`.

Todos los demás tipos (DAOs, `AppDatabase`, implementaciones concretas de repositorios, helpers HTTP de bajo nivel, etc.)  
deben considerarse detalles internos y pueden cambiar sin previo aviso.

---

## Ejemplo mínimo de integración

Los siguientes fragmentos muestran una integración básica de extremo a extremo en una app host.

### 1. Iniciar el servicio de localización y observar el historial

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

### 2. Ejecutar Pickup sobre los datos almacenados

```kotlin
// Implementación de LocationSampleSource respaldada por StorageService
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
        fromMillis = /* inicio en ms */,
        toMillis = /* fin en ms */,
        intervalSec = 60,          // rejilla de 60 segundos
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

        // Registrar el proveedor de tokens para MidnightExportWorker
        DriveTokenProviderRegistry.registerBackgroundProvider(provider)

        // Programar la exportación diaria a medianoche
        MidnightExportScheduler.scheduleNext(this)
    }
}
```

```kotlin
// Subir un archivo a Drive manualmente usando UploaderFactory (opcional)
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

Para más detalles y ejemplos completos, consulta también la versión en inglés `README.md`.

---

## Quick Start

> El código fuente bajo `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning` y `auth-*` ya está incluido y conectado en este repositorio como app de ejemplo.  
> Solo necesitas preparar algunos archivos de configuración locales y (opcionalmente) las credenciales de Google Drive para compilar y ejecutar la app.

### 0. Requisitos

- Android Studio compatible con AGP 8.11+ (o el `./gradlew` incluido)
- JDK 17 (Gradle y Kotlin usan objetivo JVM 17)
- SDK de Android con al menos API 26 instalada (el proyecto usa `compileSdk = 36`)

### 1. Preparar `local.properties`

`local.properties` almacena ajustes **específicos de la máquina del desarrollador** (principalmente la ruta al SDK de Android).  
Normalmente Android Studio lo genera automáticamente; si no existe, créalo en la raíz del proyecto.

**Ejemplo: `local.properties` (entorno local, NO commitear)**

```properties
sdk.dir=C:\\Android\\Sdk          # Ejemplo en Windows
# sdk.dir=/Users/you/Library/Android/sdk   # Ejemplo en macOS
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

### 2. Preparar `secrets.properties`

`secrets.properties` almacena **valores sensibles relacionados con autenticación y APIs**, que se inyectan en BuildConfig mediante `secrets-gradle-plugin` (fuera de control de versiones).  
El repositorio incluye `local.default.properties` como plantilla; la idea es copiarlo y editarlo:

```bash
cp local.default.properties secrets.properties   # En Windows, puedes copiarlo con el Explorador
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

> Para compilar localmente basta con usar valores ficticios.  
> Si quieres iniciar sesión realmente en Drive o usar los mapas, deberás configurar IDs de cliente / claves reales obtenidas en Cloud Console.

### 3. Preparar la integración con Google Drive

1. Crear un proyecto en Google Cloud Console  
2. Configurar la pantalla de consentimiento de OAuth  
3. Crear credenciales OAuth 2.0 (tipo **Aplicación Android** y/o **Aplicación Web**, según la implementación elegida)  
4. Configurar el ID de cliente en `secrets.properties` y/o en el código de la app  
5. Usar los siguientes scopes:
   - `https://www.googleapis.com/auth/drive.file` (acceso a archivos creados/abiertos por la app)  
   - `https://www.googleapis.com/auth/drive.metadata.readonly` (validación de ID de carpeta, etc.)

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

- Sigue las convenciones de código de Kotlin
- UI implementada con Jetpack Compose
- Persistencia con Room + DataStore
- Tareas en segundo plano con WorkManager
- Formateo y visualización comunes centralizados en `Formatters.kt`

---

## Estado de implementación de funcionalidades

| Funcionalidad                   | Estado           | Notas                                                 |
|---------------------------------|------------------|-------------------------------------------------------|
| Registro de localización (Room) | [v] Implementado | Guardado en DB Room                                   |
| Exportación diaria (GeoJSON+ZIP)| [v] Implementado | Ejecutado a medianoche por MidnightExportWorker       |
| Subida a Google Drive           | [v] Implementado | Usa el cargador Kotlin                                |
| Pickup (intervalo / número)     | [v] Implementado | Integrado con ViewModel + UseCase y la UI             |
| Autenticación Drive full-scope  | [-] En progreso  | Actualización de archivos existentes aún no soportada |
| UI: DriveSettingsScreen         | [v] Implementado | Ajustes de autenticación, carpeta y pruebas           |
| UI: PickupScreen                | [v] Implementado | Entrada de condiciones y lista de resultados          |
| UI: lista de historial          | [v] Implementado | Visualización cronológica de muestras guardadas       |

