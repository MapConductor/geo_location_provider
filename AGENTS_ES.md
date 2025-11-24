# Repository Guidelines

Este documento resume las políticas comunes, las convenciones de código y la separación de responsabilidades entre módulos al trabajar en el repositorio GeoLocationProvider.  
Antes de cambiar código, léelo una vez y sigue estas pautas en tus implementaciones.

---

## Estructura del proyecto y módulos

- El proyecto Gradle raíz `GeoLocationProvider` se compone de los siguientes módulos:
  - `:app` – Aplicación de ejemplo basada en Compose (historial, Pickup, ajustes de Drive, copias de seguridad manuales, etc.).
  - `:core` – Servicio de obtención de posición (`GeoLocationService`) y lógica relacionada con sensores. La persistencia se delega a `:storageservice`.
  - `:storageservice` – `AppDatabase` de Room, DAOs y fachada `StorageService`. Gestiona centralmente los registros de posición y el estado de exportación.
  - `:dataselector` – Lógica de selección para Pickup, etc. Filtra `LocationSample` por condiciones y construye filas de muestras representativas (`SelectedSlot`).
  - `:datamanager` – Exportación a GeoJSON, compresión ZIP, `MidnightExportWorker` / `MidnightExportScheduler` e integración con Google Drive.
  - `:deadreckoning` – Motor y API de Dead Reckoning. Se utiliza desde `GeoLocationService`.
  - `:auth-appauth` – Módulo de librería que proporciona una implementación de `GoogleDriveTokenProvider` basada en AppAuth.
  - `:auth-credentialmanager` – Módulo de librería que proporciona una implementación de `GoogleDriveTokenProvider` basada en Credential Manager + Identity.

- Dirección aproximada de dependencias:
  - `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, módulos de autenticación.
  - `:core` → `:storageservice`, `:deadreckoning`.
  - `:datamanager` → `:storageservice`, clases de integración con Drive.
  - `:dataselector` → solo la abstracción `LocationSampleSource` (la implementación concreta está en `:app`, envolviendo `StorageService`).

- El código de producción se encuentra en `src/main/java`, `src/main/kotlin` y `src/main/res` de cada módulo.  
  Los artefactos de build se generan en `build/` dentro de cada módulo.

- La configuración específica de cada máquina se define en `local.properties` en el directorio raíz.  
  Su plantilla es `local.default.properties`.  
  Los valores por defecto relacionados con secretos y autenticación viven en `secrets.properties` (presta atención a qué archivos se versionan en Git).

---

## Comandos de build, test y desarrollo

- Comandos Gradle representativos (ejecutar en la raíz):
  - `./gradlew :app:assembleDebug` – Compila el APK Debug de la app de ejemplo.
  - `./gradlew :core:assemble` / `./gradlew :storageservice:assemble` – Compila los módulos de librería.
  - `./gradlew lint` – Ejecuta análisis estático para Android / Kotlin.
  - `./gradlew test` / `./gradlew :app:connectedAndroidTest` – Ejecuta tests unitarios / tests de UI e instrumentación.

- En el desarrollo diario, se recomienda compilar y ejecutar desde Android Studio.  
  Usa Gradle por línea de comandos principalmente para CI y tareas de verificación.

---

## Estilo de código y convenciones de nombres

- Tecnologías principales: Kotlin, Jetpack Compose, Gradle Kotlin DSL.  
  Usa indentación de 4 espacios y codificación UTF-8 en todos los archivos fuente.

- Guías para la estructura de paquetes:
  - Capa de app / servicios: `com.mapconductor.plugin.provider.geolocation.*`
  - Capa de almacenamiento: `com.mapconductor.plugin.provider.storageservice.*`
  - Dead Reckoning: `com.mapconductor.plugin.provider.geolocation.deadreckoning.*`

- Convenciones de nombres:
  - Clases / objetos / interfaces – PascalCase
  - Variables / propiedades / funciones – camelCase
  - Constantes – UPPER_SNAKE_CASE
  - Composables de pantalla – sufijo `Screen`
  - ViewModels – sufijo `ViewModel`
  - Workers / Schedulers – sufijo `Worker` / `Scheduler`

- Las funciones deben tener una única responsabilidad clara y nombres descriptivos que prioricen la legibilidad.

- Elimina imports no usados y código muerto cuando los encuentres.

- En KDoc / comentarios, se recomienda dejar claros:
  - Rol / responsabilidad
  - Política de diseño
  - Uso previsto
  - Contrato (qué puede y no puede esperar el llamador)

---

## Capas y reparto de responsabilidades

### StorageService / Acceso a Room

- El acceso a `AppDatabase` / DAOs de Room debe hacerse a través de `StorageService`.
  - La única excepción es dentro del propio módulo `:storageservice` (implementaciones de DAOs y `AppDatabase`).
  - No importes tipos de DAO directamente desde `:app`, `:core`, `:datamanager` ni `:dataselector`.

- Contratos principales de `StorageService`:
  - `latestFlow(ctx, limit)` – Flow con las últimas `limit` muestras de `LocationSample`, ordenadas de la más nueva a la más antigua.
  - `getAllLocations(ctx)` – Obtiene todas las posiciones en orden ascendente por `timeMillis`. Para conjuntos de datos pequeños (exportación masiva o vista previa).
  - `getLocationsBetween(ctx, from, to, softLimit)` – Obtiene posiciones en el intervalo semiabierto `[from, to)`, ordenadas por `timeMillis` ascendente.  
    Usa esta API para conjuntos de datos grandes.
  - `insertLocation(ctx, sample)` – Inserta una muestra y registra en logs el número de filas antes/después con la etiqueta `DB/TRACE`.
  - `deleteLocations(ctx, items)` – No hace nada con listas vacías; las excepciones se propagan tal cual.
  - `ensureExportedDay` / `oldestNotUploadedDay` / `markExportedLocal` / `markUploaded` / `markExportError` – Gestionan el estado de exportación diaria.

- Se asume que todo acceso a la BD se ejecuta en `Dispatchers.IO`.  
  El código llamador no necesita cambiar de dispatcher, pero debe evitar bloquear el hilo de UI.

### dataselector / Pickup

- El módulo `:dataselector` solo depende de la interfaz `LocationSampleSource` y no debe conocer directamente Room ni `StorageService`.
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)` usa el intervalo semiabierto `[from, to)`.

- Políticas de `SelectorRepository`:
  - Cuando `intervalSec == null`: extracción directa (sin rejilla). Solo hace reducción simple y ordenación.
  - Cuando `intervalSec != null`: modo de “snap” a rejilla:
    - Con `T = intervalSec * 1000L`, se selecciona exactamente una muestra representativa dentro de una ventana `±T/2` para cada celda de la rejilla (en empate, se prioriza la muestra más antigua).
    - Para `SortOrder.NewestFirst`:
      - La rejilla se genera desde el final usando `buildTargetsFromEnd(from, to, T)`, basada en **To (endInclusive)**, sin celdas antes de `from`.
      - Al final se aplica `slots.asReversed()` para mostrar de la más nueva a la más antigua.
    - Para `SortOrder.OldestFirst`:
      - La rejilla se genera desde el inicio usando `buildTargetsFromStart(from, to, T)`, basada en **From (startInclusive)**, sin celdas más allá de `to`.
      - Los resultados se muestran tal cual, en orden ascendente (de más antigua a más nueva).

- `SelectorPrefs` persiste las condiciones de Pickup.  
  Usa las mismas unidades que `SelectorCondition` (from/to en milisegundos, `intervalSec` en segundos).

- La UI (`:app`) utiliza `:dataselector` a través de `SelectorUseCases.buildSelectedSlots(context)`.
  - `PickupScreen` usa este caso de uso para obtener resultados de Pickup sin conocer tipos de DB/DAO.

### GeoLocationService / Dead Reckoning

- La obtención real de posición la realiza `GeoLocationService` en `:core`, que funciona como un Foreground Service (FGS).
  - GNSS: usa `FusedLocationProviderClient` y `LocationRequest`.
  - IMU / Dead Reckoning: usa `HeadingSensor`, `GnssStatusSampler`, `DeadReckoning`.
  - Ajustes: se suscribe a `SettingsRepository.intervalSecFlow` / `drIntervalSecFlow` para actualizar los intervalos dinámicamente.

- API de Dead Reckoning (módulo `:deadreckoning`):
  - Interfaces públicas: `DeadReckoning`, `GpsFix`, `PredictedPoint` (paquete `...deadreckoning.api`).
  - Configuración: `DeadReckoningConfig` – objeto de parámetros que agrupa:
    - `staticAccelVarThreshold`
    - `staticGyroVarThreshold`
    - `processNoisePos`
    - `velocityGain`
    - `windowSize`
    - etc.
  - Fábrica: `DeadReckoningFactory.create(context, config = DeadReckoningConfig())`
    - Los consumidores crean `DeadReckoning` sin depender de detalles de implementación.
  - La implementación (`DeadReckoningImpl`) y el motor (`DeadReckoningEngine`, `SensorAdapter`, `DrState`, `DrUncertainty`) son internos y se pueden sustituir en el futuro.

- En `GeoLocationService`:
  - Crea la instancia de DR mediante `DeadReckoningFactory.create(applicationContext)` y conéctala a `start()` / `stop()`.
  - La política de inserción de muestras DR y la coordinación con muestras GNSS (bloqueos, etc.) deben mantenerse fuera de la API de `DeadReckoning`.

### Gestión de ajustes (SettingsRepository)

- Los intervalos de muestreo y de DR se gestionan en `storageservice.prefs.SettingsRepository`.
  - `intervalSecFlow(context)` / `drIntervalSecFlow(context)` devuelven Flows en segundos, gestionando valores por defecto y mínimos.
  - `currentIntervalMs(context)` / `currentDrIntervalSec(context)` existen por compatibilidad con código antiguo,  
    pero el código nuevo debe preferir las APIs basadas en Flow.

---

## Autenticación e integración con Drive

### GoogleDriveTokenProvider e implementaciones

- La interfaz principal de autenticación para integrar con Drive es `GoogleDriveTokenProvider`.
  - El código nuevo debería usar `CredentialManagerTokenProvider` (`:auth-credentialmanager`) o `AppAuthTokenProvider` (`:auth-appauth`) como implementación.
  - `GoogleAuthRepository` (basado en GoogleAuthUtil) es legado y solo para compatibilidad; no debe usarse en funcionalidades nuevas.

- Políticas para implementaciones de `GoogleDriveTokenProvider`:
  - La cadena de token devuelta **no** debe incluir el prefijo `"Bearer "` (se añade al construir la cabecera Authorization).
  - Para fallos “normales” (errores de red, no autenticado, falta de consentimiento, etc.) no se deben lanzar excepciones.  
    En su lugar: registrar en logs y devolver `null`.
  - Si se requiere un flujo con UI, el proveedor no debe lanzar ninguna UI.  
    Debe devolver `null` y delegar la decisión a la capa de app (Activity / Compose).

### Autenticación con Credential Manager

- `CredentialManagerAuth` es un wrapper singleton alrededor de `CredentialManagerTokenProvider`.
  - Recibe `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` vía `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID`  
    y lo trata como el ID de cliente web / servidor de Google Identity.
  - Este ID es para casos web/servidor y es distinto del client ID de AppAuth.

### Autenticación AppAuth (AppAuthTokenProvider / AppAuthAuth)

- `AppAuthTokenProvider` es la implementación de `GoogleDriveTokenProvider` en el módulo `:auth-appauth`.

- `AppAuthAuth` es su wrapper singleton, inicializado así:
  - `clientId = BuildConfig.APPAUTH_CLIENT_ID`
  - `redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"`

- `APPAUTH_CLIENT_ID` se genera a partir de la entrada `APPAUTH_CLIENT_ID` en `secrets.properties` mediante `secrets-gradle-plugin`.

#### Supuestos en Cloud Console

- El client ID de AppAuth debe crearse como **aplicación instalada (Android / otras apps instaladas)**.
  - No reutilices `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (cliente web / servidor para Credential Manager).

- Configuración obligatoria para el client de AppAuth:
  - Permitir el esquema URI personalizado `com.mapconductor.plugin.provider.geolocation`.
  - Registrar el redirect URI:  
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect`

#### AppAuthSignInActivity

- `AppAuthSignInActivity` es una Activity transparente que inicia el flujo de inicio de sesión de AppAuth y recibe el resultado.
  - Abre el navegador / Custom Tab mediante `buildAuthorizationIntent()` y pasa el Intent de callback a `handleAuthorizationResponse()`.

- En el Manifest:
  - `android:exported="true"`
  - Filtro de intents:
    - scheme: `com.mapconductor.plugin.provider.geolocation`
    - path: `/oauth2redirect`

### DriveTokenProviderRegistry / Fondo

- Para cargas a Drive en segundo plano (por ejemplo, `MidnightExportWorker`):
  - No se debe iniciar ningún flujo con UI; si `GoogleDriveTokenProvider.getAccessToken()` devuelve `null`, se trata como “no autorizado”.
  - El worker debe eliminar el archivo ZIP pero no los registros de Room, y guardar un mensaje de error en `ExportedDay.lastError`.

- Los proveedores para segundo plano se registran mediante `DriveTokenProviderRegistry.registerBackgroundProvider(...)`.  
  Toda lógica de subida en segundo plano debe obtener el proveedor a través de este registro.

- `DriveTokenProviderRegistry` actúa como singleton dentro del proceso de la app.
  - En `App.onCreate()`, llama a `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))`.

### Persistencia de ajustes de Drive (AppPrefs / DrivePrefsRepository)

- Los ajustes relacionados con Drive se persisten por dos vías:
  - `core.prefs.AppPrefs` – Ajustes heredados basados en SharedPreferences, mantiene `UploadEngine` y `folderId`.  
    Se usa principalmente en workers y rutas heredadas.
  - `datamanager.prefs.DrivePrefsRepository` – Ajustes nuevos basados en DataStore, gestiona `folderId`, `resourceKey`, `accountEmail`,  
    `uploadEngine`, `authMethod`, `tokenUpdatedAtMillis`, etc. como Flows.

- Pautas para código nuevo:
  - Para leer ajustes de Drive desde UI / casos de uso, prefiere `DrivePrefsRepository`.  
    Usa `AppPrefs` solo como capa de compatibilidad (por ejemplo, en workers).
  - Al confirmar ajustes (por ejemplo, en `DriveSettingsViewModel.validateFolder()`), propaga también los valores a  
    `AppPrefs.saveFolderId` / `saveEngine` para compartir configuración con rutas heredadas.

---

## WorkManager / MidnightExportWorker

- `MidnightExportWorker` se encarga de procesar el “backlog hasta el día anterior”.
  - Calcula fechas usando `ZoneId.of("Asia/Tokyo")` y maneja registros de un día en el intervalo `[0:00, 24:00)` en milisegundos.
  - En la primera ejecución, inicializa los últimos 365 días de `ExportedDay` mediante `StorageService.ensureExportedDay`.
  - Para cada día, obtiene registros de `LocationSample` con `StorageService.getLocationsBetween` y los convierte a GeoJSON + ZIP vía `GeoJsonExporter.exportToDownloads`.

- Política de subida y borrado:
  - Cuando la generación del ZIP local tiene éxito, llama a `markExportedLocal` (aunque el día esté vacío).
  - Para subir a Drive, comprueba la configuración actual (`AppPrefs.snapshot`) y solo sube cuando hay motor y folder ID configurados.
  - En caso de éxito, llama a `markUploaded`. En errores HTTP o fallos de autenticación, llama a `markExportError` y guarda un mensaje en `lastError`.
  - El archivo ZIP se elimina siempre (éxito o fallo) para evitar llenar el almacenamiento local.
  - Solo cuando la subida tiene éxito y hay registros para ese día, se llama a `StorageService.deleteLocations` para borrar los registros de ese día.

---

## Directrices de UI / Compose (`:app`)

- La capa de UI usa Jetpack Compose y sigue estos patrones:
  - Composables a nivel de pantalla: `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen`, etc.
  - Los `ViewModel`s se crean mediante `viewModel()` o `AndroidViewModel` y usan `viewModelScope` para trabajo asíncrono.
  - El estado se expone como StateFlow / `uiState` y se pasa a Compose.

- `App` / `MainActivity` / `AppRoot`:
  - En la clase de aplicación `App`, llama a  
    `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))`  
    en `onCreate()` para registrar el proveedor de tokens de Drive para uso en segundo plano.
  - También en `App.onCreate()`, llama a `MidnightExportScheduler.scheduleNext(this)`  
    para programar el worker de exportación diaria.
  - En `MainActivity`, solicita permisos con `ActivityResultContracts.RequestMultiplePermissions` y arranca `GeoLocationService` una vez concedidos.
  - La navegación usa una estructura `NavHost` de dos niveles:
    - El `NavHost` directamente bajo la Activity tiene destinos `"home"` y `"drive_settings"`.  
      El menú de Drive en la AppBar navega a la pantalla de ajustes de Drive.
    - El `NavHost` dentro de `AppRoot` tiene destinos `"home"` y `"pickup"`.  
      Cambia entre las pantallas Home y Pickup (mediante el botón Pickup de la AppBar).
  - La AppBar proporciona navegación a las pantallas de ajustes de Drive y Pickup.  
    El interruptor Start/Stop del servicio se encapsula en `ServiceToggleAction`.

---

## Tests, seguridad y otros

- Los tests unitarios viven en `src/test/java`; los tests de instrumentación / UI Compose en `src/androidTest/java`.
  - Para pruebas de integración con Drive, simula (`mock`) `GoogleDriveTokenProvider` y el cliente HTTP.

- Archivos confidenciales (`local.properties`, `secrets.properties`, `google-services.json`, etc.) no deben versionarse.  
  Géstionalos con plantillas + archivos locales.

- Si cambias el comportamiento de autenticación de Google o integración con Drive, asegúrate de que los scopes OAuth y redirect URIs en los README (EN/JA/ES)  
  coincidan con la configuración de Cloud Console.

- Usa **IDs de cliente separados** para AppAuth y Credential Manager:
  - Credential Manager – `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (cliente web / servidor).
  - AppAuth – `APPAUTH_CLIENT_ID` (app instalada + esquema URI personalizado).
  - Mezclarlos puede causar errores `invalid_request` (por ejemplo, “Custom URI scheme is not enabled / not allowed”).

---

## Superficie de API pública (librerías)

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`
  - `LocationSampleSource`, `SelectorRepository`, `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`:
  - Autenticación y tokens: `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - Ajustes de Drive: `DrivePrefsRepository`
  - API de Drive: `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - Subida y exportación: `Uploader`, `UploaderFactory`, `GeoJsonExporter`
  - Workers: `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`

Los tipos que no aparecen aquí deberían permanecer `internal` o no públicos siempre que sea posible,  
para que la superficie binaria pública se mantenga pequeña y estable para los consumidores de las librerías.

