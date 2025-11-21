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
  Proporciona la adquisición de localizaciones, entidades/DAOs/bd de Room y utilidades relacionadas.

- **:storageservice**  
  Contiene `AppDatabase`, los DAOs y la fachada `StorageService`, que es la única puerta de acceso a Room. Gestiona las muestras de localización (`LocationSample`) y el estado de exportación (`ExportedDay`).

- **:dataselector**  
  Implementa la lógica de filtrado y selección (Pickup) en función de condiciones (`SelectorCondition`, `SelectedSlot`, `SelectorRepository`).

- **:datamanager**  
  Se encarga de la exportación a GeoJSON, compresión ZIP, `MidnightExportWorker` y la integración con Google Drive (p. ej. `GoogleDriveTokenProvider`, `Uploader`).

- **:deadreckoning**  
  Proporciona el motor y API de Dead Reckoning (`DeadReckoning`, `GpsFix`, `PredictedPoint`), usados desde `GeoLocationService`.

- **:auth-appauth / :auth-credentialmanager**  
  Módulos de implementación de referencia de `GoogleDriveTokenProvider` basados en AppAuth o Android Credential Manager.

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
  Tareas en segundo plano basadas en WorkManager. Cada día a medianoche exportan el día anterior a GeoJSON + ZIP, suben el archivo a Google Drive y actualizan el estado correspondiente en `ExportedDay`.

- **GoogleDriveTokenProvider / Uploader / DriveApiClient**  
  Componentes centrales para la integración con Google Drive.  
  `GoogleDriveTokenProvider` abstrae la obtención de tokens de acceso, `Uploader` / `UploaderFactory` se encargan de la subida de archivos y `DriveApiClient` implementa llamadas REST como `/files` o `/about`.

---

## Public API (visión general)

Este proyecto está pensado como una biblioteca reutilizable más una aplicación de ejemplo.  
Al integrarlo en tu aplicación normalmente utilizarás los siguientes módulos y tipos:

### Módulos y puntos de entrada principales

- **`:core`**
  - `GeoLocationService` – Servicio en primer plano que registra `LocationSample` en la base de datos.
  - `UploadEngine` – Enum para configurar el tipo de exportación/subida.

- **`:storageservice`**
  - `StorageService` – Fachada única hacia Room (`LocationSample`, `ExportedDay`).
  - `LocationSample`, `ExportedDay` – Entidades principales para los registros de localización y el estado de exportación.
  - `SettingsRepository` – Gestiona los intervalos de muestreo y de Dead Reckoning.

- **`:dataselector`**
  - `SelectorCondition`, `SelectedSlot`, `SortOrder` – Modelo de dominio de las consultas Pickup.
  - `LocationSampleSource` – Abstracción sobre el origen de `LocationSample`.
  - `SelectorRepository`, `BuildSelectedSlots` – Lógica de selección y caso de uso para UI.
  - `SelectorPrefs` – Fachada DataStore para persistir las condiciones de selección.

- **`:datamanager`**
  - `GeoJsonExporter` – Exporta `LocationSample` a GeoJSON + ZIP.
  - `GoogleDriveTokenProvider` – Abstracción para obtener tokens de acceso a Drive.
  - `DriveTokenProviderRegistry` – Registro del proveedor de tokens para tareas en segundo plano.
  - `Uploader`, `UploaderFactory` – Puntos de entrada de alto nivel para la subida a Drive.
  - `DriveApiClient`, `DriveFolderId`, `UploadResult` – Ayudas para llamadas REST y modelos de resultado.
  - `MidnightExportWorker`, `MidnightExportScheduler` – Pipeline de exportación diaria.

- **`:deadreckoning`**
  - `DeadReckoning`, `GpsFix`, `PredictedPoint` – API del motor de Dead Reckoning, utilizada desde `GeoLocationService`.

- **`:auth-credentialmanager` / `:auth-appauth` (opcional)**
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider` – Implementaciones de referencia de `GoogleDriveTokenProvider`.

Todos los demás tipos (DAOs, `AppDatabase`, implementaciones concretas de repositorios, helpers HTTP de bajo nivel, etc.) deben considerarse detalles internos y pueden cambiar sin previo aviso.

### Ejemplo mínimo de integración

Los siguientes fragmentos muestran una integración básica de extremo a extremo.

#### 1. Iniciar el servicio de localización y observar el historial

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

#### 2. Ejecutar Pickup sobre los datos almacenados

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

#### 3. Configurar subida a Drive y exportación diaria

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

---

## Quick Start

> Los archivos bajo `app`, `core`, `dataselector`, `datamanager` y `storageservice` ya están incluidos en el repositorio y no requieren configuración adicional.  
> A continuación solo se describe la preparación de archivos locales y la configuración previa de Google Drive.

### 1. Preparar `local.default.properties` / `local.properties`

`local.properties` almacena ajustes **específicos de la máquina del desarrollador** (normalmente fuera de control de versiones).  
Este proyecto asume que `local.default.properties` se usa como plantilla de valores por defecto.

**Ejemplo: local.default.properties (plantilla, se puede commitear)**

```properties
# Ruta al Android SDK. Puede estar vacía o contener un valor ficticio.
sdk.dir=/path/to/Android/sdk

# Opcional: parámetros JVM de Gradle
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
```

**Ejemplo: local.properties (entorno local, NO commitear)**

```properties
sdk.dir=C:\Android\Sdk          # Ejemplo en Windows
# sdk.dir=/Users/you/Library/Android/sdk   # Ejemplo en macOS
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

Android Studio puede generar `local.properties` automáticamente; en ese caso basta con editarlo.

### 2. Preparar `secrets.properties`

`secrets.properties` almacena **valores por defecto sensibles y relacionados con autenticación** (fuera de Git).  
Algunas opciones se pueden configurar desde la UI, pero este archivo resulta útil para CI o para fijar valores por defecto.

**Ejemplo: secrets.properties (NO commitear)**

```properties
# Opcional: carpeta por defecto para subir archivos (se puede sobrescribir desde la UI)
DRIVE_DEFAULT_FOLDER_ID=

# Opcional: motor de subida por defecto
UPLOAD_ENGINE=kotlin

# Opcional: habilitar futuro soporte de full-scope (actualmente sin uso)
DRIVE_FULL_SCOPE=false

# Necesario si se usa el ejemplo con Credential Manager.
# Expuesto como BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
# mediante secrets-gradle-plugin.
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
```

Estos valores no son obligatorios para el núcleo de la biblioteca; solo son necesarios si se quiere usar la pantalla de ejemplo basada en Credential Manager.

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
./gradlew assembleDebug
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

| Funcionalidad                  | Estado         | Notas                                               |
|--------------------------------|----------------|-----------------------------------------------------|
| Registro de localización (Room)| [v] Implementado | Guardado en DB Room                              |
| Exportación diaria (GeoJSON+ZIP)| [v] Implementado | Ejecutado a medianoche por MidnightExportWorker |
| Subida a Google Drive          | [v] Implementado | Usa el cargador Kotlin                            |
| Pickup (intervalo / número)    | [v] Implementado | Integrado con ViewModel + UseCase y la UI        |
| Autenticación Drive full-scope | [-] En progreso  | Actualización de archivos existentes aún no soportada |
| UI: DriveSettingsScreen        | [v] Implementado | Ajustes de autenticación, carpeta y pruebas      |
| UI: PickupScreen               | [v] Implementado | Entrada de condiciones y lista de resultados     |
| UI: lista de historial         | [v] Implementado | Visualización cronológica de muestras guardadas  |

