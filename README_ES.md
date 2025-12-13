# GeoLocationProvider (versión en español)

GeoLocationProvider es un SDK y una aplicación de ejemplo para
**registrar, almacenar, exportar y subir datos de localización** en
Android.

Registra localización en segundo plano en una base de datos Room,
exporta los datos como GeoJSON+ZIP y puede subirlos a **Google Drive**
tanto mediante copias de seguridad nocturnas programadas como, de
forma opcional, mediante subida en tiempo real.

---

## Funcionalidades

- Obtención de localización en segundo plano  
  (intervalos de GPS y Dead Reckoning configurables).
- Almacenamiento en base de datos Room (`LocationSample`,
  `ExportedDay`).
- Exportación a formato GeoJSON con compresión ZIP opcional.
- Exportación diaria automática a medianoche (`MidnightExportWorker`).
- Exportación manual para “copias de seguridad antes de hoy” y
  “vista previa de hoy”.
- Función Pickup (muestras representativas según periodo / número de
  puntos).
- Visualización en mapa de trazas de GPS y Dead Reckoning.
- Subida a Google Drive con selección de carpeta y diagnósticos de
  autenticación.
- Gestor de subida en tiempo real con ajuste de intervalo y zona
  horaria.

---

## Arquitectura general

### Módulos

El proyecto está organizado como un proyecto Gradle multimódulo:

- `:app` – Aplicación de ejemplo basada en Jetpack Compose  
  (lista de historial, pantalla de Pickup, pantalla de Mapa, ajustes
  de Drive, ajustes de Upload, copias de seguridad manuales).
- `:core` – Servicio en primer plano `GeoLocationService`, gestión de
  sensores del dispositivo y configuración compartida (por ejemplo
  `UploadEngine`). Persiste en `:storageservice`, usa `:gps` para
  GNSS y `:deadreckoning` para Dead Reckoning.
- `:gps` – Abstracción de GPS (`GpsLocationEngine`,
  `GpsObservation`, `FusedLocationGpsEngine`) que envuelve
  `FusedLocationProviderClient` y `GnssStatus`.
- `:storageservice` – `AppDatabase` de Room, DAOs y fachada
  `StorageService`. Proporciona un único punto de entrada para los
  registros de localización y el estado de exportación.
- `:dataselector` – Lógica de selección para Pickup (rejilla
  temporal, thinning, huecos).
- `:datamanager` – Exportación a GeoJSON, compresión ZIP,
  `MidnightExportWorker` / `MidnightExportScheduler`,
  `RealtimeUploadManager`, cliente HTTP de Drive y repositorios de
  preferencias de Drive/Upload.
- `:deadreckoning` – Motor y API pública de Dead Reckoning
  (`DeadReckoning`, `GpsFix`, `PredictedPoint`,
  `DeadReckoningConfig`, `DeadReckoningFactory`).
- `:auth-appauth` / `:auth-credentialmanager` – Módulos de librería
  que proporcionan implementaciones de `GoogleDriveTokenProvider`.

Dirección aproximada de dependencias:

- `:app` → `:core`, `:dataselector`, `:datamanager`,
  `:storageservice`, `:deadreckoning`, `:gps`, módulos de
  autenticación.
- `:core` → `:gps`, `:storageservice`, `:deadreckoning`.
- `:dataselector` → la abstracción `LocationSampleSource` únicamente  
  (la implementación concreta vive en `:app`, envolviendo
  `StorageService`).
- `:datamanager` → `:core`, `:storageservice`, integración con
  Drive.

---

## Componentes principales

### Entidades y almacenamiento

- `LocationSample` – Representa una muestra de localización  
  (latitud, longitud, marca de tiempo, velocidad, estado de batería,
  calidad GNSS, etc.).
- `ExportedDay` – Gestiona el estado de exportación por día  
  (exportado localmente, resultado de subida, último error).
- `StorageService` – Fachada única alrededor de Room:
  - `latestFlow(ctx, limit)` – Flow con las `limit` muestras más
    recientes (ordenadas de más nueva a más antigua).  
    Lo usan las pantallas de historial, el mapa y el gestor de subida
    en tiempo real.
  - `getAllLocations(ctx)` – Devuelve todas las muestras en orden
    ascendente por `timeMillis`.  
    Pensado para conjuntos pequeños (vista previa de hoy, snapshots
    de depuración, etc.).
  - `getLocationsBetween(ctx, from, to, softLimit)` – Devuelve las
    muestras en el intervalo semiabierto `[from, to)` ordenadas por
    `timeMillis` de forma ascendente.  
    Se usa para exportación diaria y extracción por rango (Pickup).
  - `insertLocation`, `deleteLocations` – Inserción y borrado de
    muestras con trazas `DB/TRACE`.
  - `lastSampleTimeMillis`, `ensureExportedDay`,
    `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,
    `exportedDayCount`, `markExportedLocal`, `markUploaded`,
    `markExportError` – Gestionan el estado de exportación por día.

### Motor de GPS (`:gps`)

- `FusedLocationGpsEngine`:
  - Suscribe callbacks de `FusedLocationProviderClient` y
    `GnssStatus` y los convierte en `GpsObservation` (lat/lon,
    precisión, velocidad, rumbo, satélites usados/total, cn0Mean).
  - `GeoLocationService` depende solo de la interfaz
    `GpsLocationEngine` y transforma `GpsObservation` en:
    - Filas `LocationSample(provider="gps")`.
    - Fixes de GPS para Dead Reckoning.

### Dead Reckoning (`:deadreckoning`)

- API pública:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`.
- Implementación (`DeadReckoningImpl`) (resumen):
  - Usa una historia corta de posiciones “hold de GPS” para definir
    una trayectoria 1D (dirección GPS) y estimar velocidad efectiva.
  - Cada llamada a `submitGpsFix` reancla la posición interna a la
    última posición hold y actualiza la velocidad base.
  - Usa una ventana móvil de aceleración horizontal + velocidad
    efectiva para decidir si el dispositivo está casi estático.  
    En estado estático fija la posición DR al último hold de GPS y
    lleva la velocidad a cero.
  - Aplica `maxStepSpeedMps` como guardarraíl físico: los pasos con
    velocidad implausible se descartan.
  - `predict(from, to)` devuelve una lista de `PredictedPoint` para
    el rango solicitado; puede devolver una lista vacía antes del
    primer fix de GPS (posición absoluta aún no inicializada).

### GeoLocationService (`:core`)

- Responsabilidades de `GeoLocationService`:
  - Recibir `GpsObservation` de `GpsLocationEngine` y guardarlos como
    `LocationSample(provider="gps")` con un guardado contra
    duplicados.
  - Mantener una posición “hold de GPS” independiente de la posición
    recibida en bruto:
    - Mezcla la posición anterior con la nueva usando precisión y
      velocidad para suavizar jitter sin perder respuesta en
      movimientos rápidos.
    - Usa la posición hold (no la bruta) para construir `GpsFix` y
      pasarlo a `DeadReckoning`.
  - Crear la instancia de DR con `DeadReckoningFactory.create` y
    controlar su ciclo de vida (`start()` / `stop()` /
    `submitGpsFix()` / `predict()`).
  - Ticker de DR:
    - El intervalo en segundos se obtiene de
      `SettingsRepository.drIntervalSecFlow`.
    - `drIntervalSec == 0` → **Dead Reckoning desactivado (solo
      GPS)**, el ticker se detiene y no se insertan muestras DR.
    - `drIntervalSec > 0` → se ejecuta un loop que llama periódicamente
      a `dr.predict(...)` y transforma el último `PredictedPoint` en
      `LocationSample(provider="dead_reckoning")`, con su propio
      guardado contra duplicados.
  - Para cada tick de DR:
    - Obtiene `dr.isLikelyStatic()` y lo publica en `DrDebugState`  
      para que la superposición de depuración en el mapa pueda
      mostrar `Static: YES/NO`.

### Exportación / subida (`:datamanager`)

- Exportación diaria (`MidnightExportWorker` / `MidnightExportScheduler`):
  - Zona horaria: se lee de `UploadPrefs.zoneId` (ID IANA, por
    defecto `Asia/Tokyo`).
  - Para cada día hasta “ayer”:
    - Usa `StorageService.ensureExportedDay`,
      `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,
      `exportedDayCount`, `lastSampleTimeMillis` para decidir qué
      días procesar y qué resumen (`backupStatus`) escribir en
      `DrivePrefsRepository`.
    - Carga muestras con `getLocationsBetween`, las exporta como
      GeoJSON+ZIP a Descargas mediante `GeoJsonExporter`, y marca
      `markExportedLocal`.
    - Resuelve la carpeta efectiva de Drive usando
      `DrivePrefsRepository` (carpeta configurada en la UI) y
      `AppPrefs.folderId` como reserva.
    - Crea un uploader con `UploaderFactory` y `UploadEngine.KOTLIN`,
      sube el ZIP y marca el resultado con `markUploaded` o
      `markExportError` (incluyendo `lastError`).
    - Borra siempre el ZIP para no llenar el almacenamiento local.
    - Borra filas de `LocationSample` para ese día solo si la subida
      ha tenido éxito y había registros.

- “Backup days before today”:
  - Llama a `MidnightExportWorker.runNow(context)` en un modo que
    fuerza un escaneo desde el primer día con muestras hasta ayer,
    usando `lastSampleTimeMillis` para delimitar el rango.

- Subida en tiempo real (`RealtimeUploadManager`):
  - Se suscribe a:
    - `UploadPrefsRepository.scheduleFlow`,
      `intervalSecFlow`, `zoneIdFlow`.
    - `SettingsRepository.intervalSecFlow` y
      `drIntervalSecFlow`.
    - `StorageService.latestFlow(ctx, limit = 1)`.
  - Solo reacciona cuando:
    - `UploadSchedule.REALTIME` está seleccionado.
    - Drive está configurado (cuenta + carpeta + engine válidos).
  - Cálculo del intervalo efectivo:
    - `intervalSec <= 0` → subir en cada nueva muestra.
    - Para valores > 0, actúa como un cooldown temporal.
    - Si `intervalSec` coincide con el intervalo de muestreo activo
      (DR si está activado, GPS en caso contrario), también se
      interpreta como “cada muestra”.
  - Proceso:
    - Cargar todas las muestras con `getAllLocations`.
    - Formatear a GeoJSON y escribir a un archivo temporal
      `YYYYMMDD_HHmmss.json` en `cacheDir` usando la hora de la
      última muestra y `zoneId`.
    - Resolver carpeta de Drive combinando
      `DrivePrefsRepository.folderId` y `AppPrefs.folderId`.
    - Crear `Uploader` con `UploaderFactory.create(context,
      appPrefs.engine)`; internamente usa
      `DriveTokenProviderRegistry` para obtener un
      `GoogleDriveTokenProvider`.
    - Subir el JSON; siempre se elimina el archivo temporal al final.
    - Si la subida tiene éxito, borrar las muestras subidas con
      `deleteLocations`.

### Ajustes de Drive / Upload

- Ajustes de Drive:
  - `AppPrefs` (SharedPreferences, legado) – `UploadEngine` /
    `folderId` etc.
  - `DrivePrefsRepository` (DataStore) – `folderId`,
    `folderResourceKey`, `accountEmail`, `uploadEngineName`,
    `authMethod`, `tokenUpdatedAtMillis`, `backupStatus`.
  - `DriveSettingsScreen` / `DriveSettingsViewModel` usan
    principalmente `DrivePrefsRepository` y replican `folderId` /
    engine en `AppPrefs` para compatibilidad.

- Ajustes de Upload:
  - `UploadPrefsRepository` (DataStore) – `UploadSchedule` (`NONE`
    / `NIGHTLY` / `REALTIME`), `intervalSec`, `zoneId`.
  - `UploadSettingsScreen` / `UploadSettingsViewModel`:
    - Solo permiten activar Upload si Drive está configurado (hay
      `accountEmail`).
    - Permiten elegir `UploadSchedule` (NONE/NIGHTLY/REALTIME).
    - Validan y acotan `intervalSec` (0 o 1–86400).
    - Permiten fijar `zoneId` (ID IANA) usada tanto por la
      exportación nocturna como por la vista previa de hoy.

---

## UI / Compose

- `MainActivity`:
  - Contiene un `NavHost` a nivel de Activity con rutas `"home"`,
    `"drive_settings"`, `"upload_settings"`.
  - Solicita permisos en tiempo de ejecución mediante
    `ActivityResultContracts.RequestMultiplePermissions` y, tras
    concederse, arranca `GeoLocationService`.

- `AppRoot`:
  - Define un `NavHost` interno con rutas `"home"`, `"pickup"`,
    `"map"`.
  - AppBar:
    - Muestra el título “GeoLocation”, “Pickup” o “Map” según la
      ruta actual.
    - Muestra un botón atrás en Pickup y Map.
    - En la ruta home muestra botones `Map`, `Pickup`, `Drive`,
      `Upload` y el componente `ServiceToggleAction` para iniciar /
      detener el servicio.

- `GeoLocationProviderScreen`:
  - Muestra controles de intervalos (GPS y DR) y la lista de
    historial.

- `PickupScreen`:
  - Usa `SelectorUseCases` para leer condiciones de Pickup y mostrar
    los resultados.

- `MapScreen` / `GoogleMapsExample`:
  - Usa `GoogleMapsView` de MapConductor con backend de Google Maps.
  - Fila superior:
    - Checkboxes para `GPS` y `DeadReckoning`.
    - Campo `Count` (1–5000).
    - Botones `Apply` / `Cancel`.
  - `Apply`:
    - Bloquea los controles y dibuja polilíneas por proveedor usando
      hasta `Count` muestras más recientes:
      - GPS: polilínea azul, más gruesa, dibujada primero (detrás).
      - DR : polilínea roja, más fina, dibujada después (delante).
    - El orden de conexión es estrictamente por tiempo (`timeMillis`)
      y no por distancia.
  - `Cancel`:
    - Borra las polilíneas y desbloquea los controles, manteniendo
      la posición / zoom actuales.
  - Superposición de depuración:
    - Muestra `GPS`, `DR`, `ALL` como `mostrados / total en BD`.
    - Muestra el flag de estático de `DrDebugState`
      (`Static: YES/NO`).
    - Muestra la distancia DR–GPS, la precisión GPS y una escala de
      peso GPS interna.
  - Círculo de precisión:
    - Centro: última muestra GPS.
    - Radio: `accuracy` en metros.
    - Trazo azul fino y relleno azul semitransparente.

---

## Superficie de API pública (resumen)

Para usar GeoLocationProvider como librería, se recomienda depender
sobre todo de los siguientes módulos y tipos:

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`,
    `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`,
    `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`:
  - Autenticación / tokens:
    `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - Ajustes:
    `DrivePrefsRepository`, `UploadPrefsRepository`,
    `UploadSchedule`
  - API de Drive:
    `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - Exportación / subida:
    `UploadEngine`, `GeoJsonExporter`, `Uploader`, `UploaderFactory`,
    `RealtimeUploadManager`
  - Trabajos en segundo plano:
    `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`:
  - `GpsLocationEngine`, `GpsObservation`,
    `FusedLocationGpsEngine`

Los demás tipos (DAOs, motores internos, helpers HTTP, etc.) deben
tratarse como detalles de implementación y pueden cambiar sin aviso.

---

## Notas de desarrollo y configuración

- El proyecto está implementado principalmente en Kotlin con
  Jetpack Compose, Room, DataStore y WorkManager.
- Todo el **código de producción** (Kotlin / Java / XML / scripts
  Gradle) se escribe usando solo caracteres ASCII;  
  el texto multilingüe (español / japonés, etc.) se limita a archivos
  de documentación (`*.md`).
- Cuando cambies el comportamiento de autenticación de Google o la
  integración con Drive, asegúrate de que los scopes OAuth y las
  redirect URIs de los README (EN/JA/ES) coinciden con la
  configuración en Google Cloud Console.

