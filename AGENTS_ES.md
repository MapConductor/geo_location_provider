# Repository Guidelines (versión en español)

Este documento resume las **políticas comunes, las convenciones de código y la separación de responsabilidades entre módulos** al trabajar en el repositorio GeoLocationProvider.  
Antes de cambiar código, léelo una vez y sigue estas pautas al implementar cambios.

---

## Estructura del proyecto y módulos

- El proyecto Gradle raíz `GeoLocationProvider` se compone de los siguientes módulos:
  - `:app` – Aplicación de ejemplo basada en Compose (historial, Pickup, ajustes de Drive, copias de seguridad manuales, etc.).
  - `:core` – Servicio de obtención de localización (`GeoLocationService`) y lógica relacionada con sensores. La persistencia se delega a `:storageservice`.
  - `:storageservice` – `AppDatabase` de Room, DAOs y fachada `StorageService`. Gestiona centralmente los registros de localización y el estado de exportación.
  - `:dataselector` – Lógica de selección para Pickup. Filtra `LocationSample` por condiciones y construye filas de muestras representativas (`SelectedSlot`).
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

- La configuración específica de cada máquina (por ejemplo, la ruta al SDK de Android) se define en `local.properties` en la raíz.  
  Android Studio suele generar este archivo automáticamente; si no existe, créalo manualmente.

- Los secretos y la configuración relacionada con autenticación viven en `secrets.properties`, que **no debe versionarse**.  
  La plantilla para este archivo es `local.default.properties`: cópiala a `secrets.properties` y reemplaza los valores con credenciales reales.

---

## Comandos de build, test y desarrollo

- Comandos Gradle representativos (ejecutar en la raíz):
  - `./gradlew :app:assembleDebug` – Compila el APK Debug de la app de ejemplo.
  - `./gradlew :core:assemble` / `./gradlew :storageservice:assemble` – Compila los módulos de librería.
  - `./gradlew lint` – Ejecuta análisis estático de Android / Kotlin.
  - `./gradlew test` / `./gradlew :app:connectedAndroidTest` – Ejecuta tests unitarios / tests de UI e instrumentación.

- Para el desarrollo diario se recomienda compilar y ejecutar desde Android Studio.  
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

### Política de comentarios y codificación

- Todo el código de producción (Kotlin / Java / XML / scripts Gradle, etc.) debe escribirse usando **solo caracteres ASCII**.  
  No uses caracteres multibyte en código, comentarios ni literales de cadena.
- El contenido multilingüe (japonés / español, etc.) se permite únicamente en archivos de documentación como `README_JA.md`, `README_ES.md` y otros `*.md`.
- Para APIs públicas y clases principales, prefiere KDoc (`/** ... */`); para notas internas de implementación, usa comentarios de una línea `// ...`.
- Los encabezados de sección dentro del código deben seguir un estilo simple (por ejemplo, `// ---- Section ----` o `// ------------------------`) evitando banners decorativos.
- Al refactorizar o mover lógica entre módulos, intenta mantener **el mismo nivel de detalle y tono en los comentarios** para componentes equivalentes.

---

## Capas y reparto de responsabilidades

### StorageService / Acceso a Room

- El acceso a `AppDatabase` / DAOs de Room debe hacerse a través de `StorageService`.
  - La única excepción es dentro del propio módulo `:storageservice` (implementaciones de DAO y `AppDatabase`).
  - No importes tipos de DAO directamente desde `:app`, `:core`, `:datamanager` ni `:dataselector`.

- Contratos principales de `StorageService`:
  - `latestFlow(ctx, limit)` – Devuelve un Flow con las últimas `limit` muestras de `LocationSample`, ordenadas de más nuevas a más antiguas.
  - `getAllLocations(ctx)` – Devuelve todas las localizaciones en orden ascendente de `timeMillis`. Pensado para conjuntos pequeños (exportaciones puntuales, vistas previas).
  - `getLocationsBetween(ctx, from, to, softLimit)` – Devuelve localizaciones en el intervalo `[from, to)` (intervalo semiabierto), ordenadas por `timeMillis` ascendente.  
    Utiliza esta API para conjuntos de datos grandes.
  - `insertLocation(ctx, sample)` – Inserta una muestra y registra en el log el número de filas antes y después con la etiqueta `DB/TRACE`.
  - `deleteLocations(ctx, items)` – No hace nada si la lista está vacía; propaga las excepciones directamente.
  - `ensureExportedDay` / `oldestNotUploadedDay` / `markExportedLocal` / `markUploaded` / `markExportError` – Gestionan el estado de exportación diaria.

- Se asume que todo acceso a la base de datos se ejecuta en `Dispatchers.IO`.  
  Los llamadores no necesitan cambiar de dispatcher, pero deben evitar bloquear el hilo de UI.

### dataselector / Pickup

- El módulo `:dataselector` depende únicamente de la interfaz `LocationSampleSource` y no debe conocer Room ni `StorageService` directamente.
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)` usa el intervalo semiabierto `[from, to)`.

- Políticas de `SelectorRepository`:
  - Cuando `intervalSec == null`: extracción directa (sin rejilla). Solo realiza un adelgazamiento simple y ordenación.
  - Cuando `intervalSec != null`: modo de ajuste a rejilla (grid-snapping).
    - Sea `T = intervalSec * 1000L`. Para cada rejilla, se selecciona exactamente una muestra representativa dentro de una ventana `±T/2` (si hay varias, se prefiere la más temprana).
    - Para `SortOrder.NewestFirst`:
      - Las rejillas se generan desde el final usando `buildTargetsFromEnd(from, to, T)`, basadas en **To (endInclusive)**, sin crear rejillas anteriores a `from`.
      - Los resultados se invierten con `slots.asReversed()` para mostrarlos de más nuevo a más antiguo.
    - Para `SortOrder.OldestFirst`:
      - Las rejillas se generan desde el inicio usando `buildTargetsFromStart(from, to, T)`, basadas en **From (startInclusive)**, sin crear rejillas posteriores a `to`.
      - Los resultados se muestran tal cual en orden ascendente (de más antiguo a más nuevo).

- `SelectorPrefs` persiste las condiciones de Pickup.  
  Utiliza las mismas unidades que `SelectorCondition` (from/to en milisegundos, `intervalSec` en segundos).

- La UI (`:app`) usa dataselector a través de `SelectorUseCases.buildSelectedSlots(context)`.  
  `PickupScreen` usa este caso de uso para obtener resultados de Pickup sin conocer tipos de DB/DAO.

### GeoLocationService / Dead Reckoning

- La obtención real de localización la realiza `GeoLocationService` en el módulo `:core`, que se ejecuta como servicio en primer plano (FGS).
  - GNSS: usa `FusedLocationProviderClient` y `LocationRequest`.
  - IMU / Dead Reckoning: usa `HeadingSensor`, `GnssStatusSampler`, `DeadReckoning`.
  - Ajustes: se suscribe a `SettingsRepository.intervalSecFlow` / `drIntervalSecFlow` para actualizar dinámicamente los intervalos.

- API de Dead Reckoning (módulo `:deadreckoning`):
  - Interfaces públicas: `DeadReckoning`, `GpsFix`, `PredictedPoint` (paquete `...deadreckoning.api`).
  - Configuración: `DeadReckoningConfig` – objeto de parámetros que agrupa:
    - `staticAccelVarThreshold`
    - `staticGyroVarThreshold`
    - `processNoisePos`
    - `velocityGain`
    - `maxStepSpeedMps` (límite físico de velocidad por paso; <= 0 lo desactiva)
    - `debugLogging` (logs de depuración opcionales del motor DR)
    - `windowSize`
    - etc.
  - Factoría: `DeadReckoningFactory.create(context, config = DeadReckoningConfig())`  
    Los llamadores crean `DeadReckoning` sin depender de detalles de implementación.
  - Implementación (`DeadReckoningImpl`) y motor (`DeadReckoningEngine`, `SensorAdapter`, `DrState`, `DrUncertainty`) son internos y pueden cambiar en el futuro.

- En `GeoLocationService`:
  - Crea la instancia de DR mediante `DeadReckoningFactory.create(applicationContext)` y la conecta a `start()` / `stop()`.
  - Las políticas de inserción de muestras de DR y la coordinación con muestras GNSS (bloqueos, etc.) deben permanecer fuera de la API de `DeadReckoning`.
  - Los fixes de GPS se tratan como anclas fuertes: cada fix restablece la posición interna de DR a la lat/lon de GPS y mezcla la velocidad con `velocityGain`.
  - El motor de DR descarta pasos de IMU cuya velocidad implícita por paso supere `maxStepSpeedMps`, de modo que los saltos "físicamente imposibles" se filtran dentro de `:deadreckoning`.
  - `predict()` puede devolver una lista vacía antes del primer fix de GPS (los llamadores deben tolerar el estado "sin posición absoluta todavía").
  - Para casos de uso estáticos (por ejemplo, el dispositivo sobre la mesa), `GeoLocationService` usa `DeadReckoning.isLikelyStatic()` y la última posición de GPS para limitar la deriva de DR dentro de un radio pequeño alrededor del ancla (aprox. 2 metros).

### Gestión de ajustes (SettingsRepository)

- Los intervalos de muestreo y de Dead Reckoning se gestionan en `storageservice.prefs.SettingsRepository`.
  - `intervalSecFlow(context)` / `drIntervalSecFlow(context)` devuelven Flows en **segundos**, gestionando valores por defecto y mínimos.
  - `currentIntervalMs(context)` / `currentDrIntervalSec(context)` se mantienen por compatibilidad,  
    pero el código nuevo debería preferir las APIs basadas en Flow.

---

## Autenticación e integración con Drive

### GoogleDriveTokenProvider e implementaciones

- La interfaz principal de autenticación para Drive es `GoogleDriveTokenProvider`.
  - El código nuevo debería usar `CredentialManagerTokenProvider` (`:auth-credentialmanager`) o `AppAuthTokenProvider` (`:auth-appauth`) como implementación.
  - La implementación heredada `GoogleAuthRepository` (basada en GoogleAuthUtil) existe solo por compatibilidad y **no** debe usarse en nuevas funciones.

- Políticas para implementaciones de `GoogleDriveTokenProvider`:
  - Las cadenas de tokens devueltas **no** deben incluir el prefijo `"Bearer "` (se añade al construir la cabecera Authorization).
  - Para fallos normales (errores de red, usuario no autenticado, falta de consentimiento, etc.), **no** se deben lanzar excepciones.  
    En su lugar, registra el fallo en logs y devuelve `null`.
  - Si se requiere un flujo de UI, el proveedor no debe lanzar la UI directamente.  
    Debe devolver `null` y delegar la decisión a la capa de app (Activity / Compose).

### Autenticación con Credential Manager

- `CredentialManagerAuth` es un wrapper singleton alrededor de `CredentialManagerTokenProvider`.
  - Recibe `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` vía `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID`  
    y lo trata como el **ID de cliente web / servidor** de Google Identity.
  - Este ID es distinto del client ID usado por AppAuth; no deben mezclarse.

### Autenticación con AppAuth (AppAuthTokenProvider / AppAuthAuth)

- `AppAuthTokenProvider` es la implementación de `GoogleDriveTokenProvider` incluida en el módulo `:auth-appauth`.

- Su wrapper `AppAuthAuth` se inicializa como:
  - `clientId = BuildConfig.APPAUTH_CLIENT_ID`
  - `redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"`

- `APPAUTH_CLIENT_ID` se genera a partir de la clave `APPAUTH_CLIENT_ID` en `secrets.properties` mediante `secrets-gradle-plugin`.

#### Supuestos en Cloud Console

- El client ID de AppAuth debe crearse como **aplicación instalada (Android / otras apps instaladas)**.
  - No reutilices `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (client ID web / servidor) para AppAuth.

- Ajustes necesarios para el client ID de AppAuth:
  - Permitir el esquema de URI personalizado `com.mapconductor.plugin.provider.geolocation`.
  - Registrar la URI de redirección:  
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect`

#### AppAuthSignInActivity

- `AppAuthSignInActivity` es una Activity transparente que inicia el flujo de inicio de sesión de AppAuth y recibe el resultado.
  - Abre el navegador / Custom Tab mediante `buildAuthorizationIntent()` y pasa el `Intent` de callback a `handleAuthorizationResponse()`.

- En el manifest:
  - `android:exported="true"`
  - Intent filter:
    - `scheme="com.mapconductor.plugin.provider.geolocation"`
    - `path="/oauth2redirect"`

### DriveTokenProviderRegistry / segundo plano

- Para subidas en segundo plano (por ejemplo, `MidnightExportWorker`):
  - Nunca inicies flujos de UI; si `GoogleDriveTokenProvider.getAccessToken()` devuelve `null`, trátalo como "no autorizado".
  - El worker debe borrar el archivo ZIP pero no los registros de Room, y almacenar un mensaje de error en `ExportedDay.lastError`.

- Registra los proveedores de tokens para segundo plano mediante `DriveTokenProviderRegistry.registerBackgroundProvider(...)`.  
  Toda lógica de subida en segundo plano debe obtener su proveedor a través de este registro.

- `DriveTokenProviderRegistry` se comporta como un singleton dentro del proceso de la app.
  - En `App.onCreate()`, llama a `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))`.

### Persistencia de ajustes de Drive (AppPrefs / DrivePrefsRepository)

- Los ajustes relacionados con Drive se guardan en dos sistemas:
  - `core.prefs.AppPrefs` – Ajustes heredados basados en SharedPreferences, que almacenan `UploadEngine` y `folderId`.  
    Se usan principalmente desde workers y rutas heredadas.
  - `datamanager.prefs.DrivePrefsRepository` – Ajustes más nuevos basados en DataStore.  
    Gestiona `folderId`, `resourceKey`, `accountEmail`, `uploadEngine`, `authMethod`, `tokenUpdatedAtMillis`, etc., como Flows.

- Recomendaciones para código nuevo:
  - Para leer ajustes de Drive desde UI / casos de uso, utiliza `DrivePrefsRepository`.  
    Usa `AppPrefs` solo como capa de compatibilidad para workers u otras rutas heredadas.
  - Al confirmar ajustes (por ejemplo, en `DriveSettingsViewModel.validateFolder()`), además de guardar en `DrivePrefsRepository`,  
    propaga también a `AppPrefs.saveFolderId` / `saveEngine` para compartir configuración con rutas antiguas.

---

## WorkManager / MidnightExportWorker

- `MidnightExportWorker` es responsable de procesar el "backlog hasta el día anterior".
  - Calcula fechas usando `ZoneId.of("Asia/Tokyo")` y trata los registros de un día en el intervalo `[0:00, 24:00)` en milisegundos.
  - En la primera ejecución, inicializa los últimos 365 días de `ExportedDay` mediante `StorageService.ensureExportedDay`.
  - Para cada día, obtiene muestras `LocationSample` con `StorageService.getLocationsBetween` y las convierte a GeoJSON + ZIP mediante `GeoJsonExporter.exportToDownloads`.

- Política de subida y borrado:
  - Una vez que la generación del ZIP local tiene éxito, llama a `markExportedLocal` (aunque el día esté vacío).
  - Para subir a Drive, comprueba la configuración actual (`AppPrefs.snapshot`) y solo sube cuando hay motor y folder ID configurados.
  - En caso de éxito, llama a `markUploaded`. En errores HTTP o fallos de autenticación, llama a `markExportError` y guarda un mensaje en `lastError`.
  - El archivo ZIP se elimina siempre (éxito o fallo) para evitar llenar el almacenamiento local.
  - Solo cuando la subida tiene éxito y hay registros para ese día se llama a `StorageService.deleteLocations` para borrar los registros de ese día.

---

## Directrices de UI / Compose (`:app`)

- La capa de UI usa Jetpack Compose y sigue estos patrones:
  - Composables a nivel de pantalla: `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen`, etc.
  - Los `ViewModel`s se crean mediante `viewModel()` o `AndroidViewModel` y usan `viewModelScope` para trabajo asíncrono.
  - El estado se expone mediante `StateFlow` / `uiState` y se pasa a Compose.

- Pantalla de mapa (`MapScreen` / `GoogleMapsExample`):
  - Usa MapConductor (`GoogleMapsView`) para el renderizado.
  - La fila superior contiene checkboxes para `GPS` y `DeadReckoning`, un campo numérico `Count (1-1000)` y un botón `Apply` / `Cancel`.
  - En la primera entrada no se muestran polilíneas. Al pulsar `Apply`, los controles se bloquean y se dibujan hasta `Count` muestras más recientes:
    - DeadReckoning: polilínea roja, trazado más fino, dibujada **después** de GPS (delante).
    - GPS: polilínea azul, trazado más grueso, dibujada **antes** de DR (detrás).
  - Los puntos se conectan estrictamente en orden temporal (`timeMillis`), no por distancia. Las muestras nuevas se añaden y las más antiguas se descartan para que el total no supere `Count`.
  - Al pulsar `Cancel`, las polilíneas se eliminan y los controles se desbloquean, pero la posición / zoom de la cámara se mantiene.
  - Una superposición de depuración en la esquina superior derecha muestra los contadores `GPS`, `DR` y `ALL` como `mostrados / total en BD`.

- `App` / `MainActivity` / `AppRoot`:
  - En la clase de aplicación `App`, llama a  
    `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))`  
    en `onCreate()` para registrar el proveedor de tokens de Drive para uso en segundo plano.
  - También en `App.onCreate()`, llama a `MidnightExportScheduler.scheduleNext(this)`  
    para programar el worker de exportación diaria.
  - En `MainActivity`, solicita permisos mediante `ActivityResultContracts.RequestMultiplePermissions` y arranca `GeoLocationService` una vez concedidos.
  - La navegación usa una estructura `NavHost` de dos niveles:
    - El `NavHost` directamente bajo la Activity tiene destinos `"home"` y `"drive_settings"`.  
      El menú de Drive en la AppBar navega a la pantalla de ajustes de Drive.
    - El `NavHost` dentro de `AppRoot` tiene destinos `"home"` y `"pickup"`.  
      Cambia entre las pantallas Home y Pickup (mediante el botón Pickup de la AppBar).
  - La AppBar proporciona navegación a las pantallas de ajustes de Drive y Pickup.  
    El interruptor Start/Stop del servicio se encapsula en `ServiceToggleAction`.

---

## Tests, seguridad y otros

- Los tests unitarios viven en `src/test/java`; los tests de instrumentación / Compose UI en `src/androidTest/java`.
  - Para pruebas de integración con Drive, simula (`mock`) `GoogleDriveTokenProvider` y el cliente HTTP.

- Archivos confidenciales (`local.properties`, `secrets.properties`, `google-services.json`, etc.) no deben versionarse.  
  Géstionalos mediante plantillas y archivos locales.

- Si cambias el comportamiento de autenticación de Google o la integración con Drive, asegúrate de que los scopes OAuth y las redirect URIs en los README (EN/JA/ES)  
  coinciden con la configuración en Cloud Console.

- Usa **IDs de cliente separados** para AppAuth y Credential Manager:
  - Credential Manager – `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (cliente web / servidor).
  - AppAuth – `APPAUTH_CLIENT_ID` (app instalada + esquema URI personalizado).
  - Mezclarlos puede causar errores `invalid_request` (por ejemplo, "Custom URI scheme is not enabled / not allowed").

---

## Superficie de API pública (librería)

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
para mantener pequeña y estable la superficie de API binaria visible para los consumidores de la librería.
