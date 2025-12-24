# Repository Guidelines (resumen en español)

Este documento resume en español la **estructura por módulos, la
separación de responsabilidades y las políticas de código** del
repositorio GeoLocationProvider.  
La referencia completa y siempre actualizada es `AGENTS.md` (inglés).

---

## Estructura del proyecto y módulos

- Proyecto Gradle raíz `GeoLocationProvider`:
  - `:app`  
    Aplicación de ejemplo basada en Jetpack Compose  
    (historial, Pickup, mapa, ajustes de Drive, ajustes de Upload,
    copia de seguridad manual).
  - `:core`  
    Servicio en primer plano `GeoLocationService`, gestión de
    sensores del dispositivo y configuración compartida
    (`UploadEngine`). Persiste en `:storageservice`, usa `:gps` y
    `:deadreckoning`.
  - `:gps`  
    Abstracción de GPS (`GpsLocationEngine`, `GpsObservation`,
    `FusedLocationGpsEngine`).
  - `:storageservice`  
    `AppDatabase` de Room, DAOs y fachada `StorageService`. Gestiona
    `LocationSample` (historial) y `ExportedDay` (estado de
    exportación por día).
  - `:dataselector`  
    Lógica de selección para Pickup sobre la abstracción
    `LocationSampleSource`.
  - `:datamanager`  
    Exportación GeoJSON / GPX, compresión ZIP,
    `MidnightExportWorker` / `MidnightExportScheduler`,
    `RealtimeUploadManager`, cliente HTTP de Drive y repositorios de
    preferencias de Drive/Upload.
  - `:deadreckoning`  
    Motor y API de Dead Reckoning (`DeadReckoning`, `GpsFix`,
    `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`).
  - `:auth-appauth` / `:auth-credentialmanager`  
    Implementaciones de `GoogleDriveTokenProvider`.
  - `mapconductor-core-src`  
    Código fuente de MapConductor vendorizado, usado solo desde
    `:app` (tratar como solo lectura).

Dirección de dependencias (aproximada):

- `:app` → `:core`, `:dataselector`, `:datamanager`,
  `:storageservice`, `:deadreckoning`, `:gps`, módulos de auth,
  MapConductor.
- `:core` → `:gps`, `:storageservice`, `:deadreckoning`.
- `:dataselector` → solo la abstracción `LocationSampleSource`
  (implementada en `:app` envolviendo `StorageService`).
- `:datamanager` → `:core`, `:storageservice`, integración con
  Drive.

---

## StorageService / acceso a Room

- Todo acceso a `AppDatabase` / DAOs desde fuera de
  `:storageservice` debe hacerse a través de `StorageService`.
  - Dentro de `:storageservice` se permiten DAOs directos.
  - No importes DAOs desde `:app`, `:core`, `:datamanager` ni
    `:dataselector`.

API clave (resumen):

- `latestFlow(ctx, limit)`  
  Flow con las `limit` muestras más recientes de `LocationSample`
  (orden descendente por `timeMillis`).
- `getAllLocations(ctx)`  
  Todas las muestras en orden ascendente por `timeMillis`
  (solo para conjuntos pequeños: vista previa, snapshot).
- `getLocationsBetween(ctx, from, to, softLimit)`  
  Muestras en el intervalo semiabierto `[from, to)` ordenadas por
  `timeMillis`.
- `insertLocation`, `deleteLocations`  
  Inserción/borrado de filas con trazas `DB/TRACE`.
- `lastSampleTimeMillis`, `ensureExportedDay`,
  `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,
  `exportedDayCount`, `markExportedLocal`, `markUploaded`,
  `markExportError`  
  Gestión del estado por día en `ExportedDay`.

Todos los accesos a BD se ejecutan en `Dispatchers.IO` dentro de
`StorageService`.

---

## GeoLocationService / Dead Reckoning / ajustes de intervalos

- `GeoLocationService` (`:core`) es un servicio en primer plano que
  orquesta:
  - GPS a través de `GpsLocationEngine` / `FusedLocationGpsEngine`.
  - Dead Reckoning mediante `DeadReckoning` (módulo `:deadreckoning`).
  - Sensores auxiliares como batería y heading.
- Dead Reckoning:
  - La configuración de intervalo se almacena en
    `SettingsRepository.drIntervalSecFlow` /
    `SettingsRepository.currentDrIntervalSec`.
  - `drIntervalSec == 0`  Einterpreta “DR desactivado (solo GPS)” y
    no se generan muestras `dead_reckoning`.
  - `drIntervalSec > 0` y `DrMode.Prediction` activan un ticker que
    llama periódicamente a `dr.predict(fromMillis, toMillis)` y usa el
    último `PredictedPoint` para insertar muestras
    `"dead_reckoning"` entre fixes de GPS.
  - `drIntervalSec > 0` y `DrMode.Completion` desactivan el ticker y,
    en cada nuevo fix de GPS, usan `dr.predict(fromMillis, toMillis)`
    para rellenar el tramo GPS–GPS con muestras `dead_reckoning`
    corrigiendo el trazado para terminar exactamente en la posición
    GPS “hold” más reciente.
- Modo de DR (`DrMode`):
  - Persistido en `SettingsRepository` como `Prediction` o
    `Completion` (vía `drModeFlow` / `currentDrMode`).
  - `GeoLocationService` se suscribe tanto al intervalo (GPS/DR) como
    al modo para activar/parar el ticker según la combinación
    actual.
- `IntervalSettingsViewModel`:
  - Expone campos de texto para intervalos de GPS y DR, un selector de
    modo de Dead Reckoning (Predicción vs Relleno) y un botón
    “Save & Apply” que guarda en DataStore y notifica al servicio.

---

## Exportación y subida (UploadPrefs / formatos)

### UploadPrefsRepository / ajustes de Upload

- `UploadPrefsRepository` expone ajustes de subida basados en DataStore:
  - `scheduleFlow` (`UploadSchedule.NONE` / `NIGHTLY` / `REALTIME`).
  - `intervalSecFlow` (0 o 1–86400 segundos).
  - `zoneIdFlow` (ID de zona horaria IANA; por defecto `Asia/Tokyo`).
  - `outputFormatFlow` (`UploadOutputFormat.GEOJSON` / `GPX`).
- `UploadSettingsScreen` / `UploadSettingsViewModel` permiten editar:
  - Activar/desactivar Upload (solo posible cuando Drive está
    configurado — `DrivePrefs.accountEmail` no vacío).
  - Planificación: nightly vs realtime.
  - Intervalo en segundos para realtime.
  - Zona horaria compartida con la exportación diaria y Today preview.
  - Formato de salida: GeoJSON vs GPX.

### MidnightExportWorker / MidnightExportScheduler

- `MidnightExportWorker` procesa “backlog hasta ayer E
  - Usa `UploadPrefs.zoneId` para determinar la zona horaria y
    procesa días `[00:00, 24:00)`.
  - Utiliza `StorageService.ensureExportedDay`,
    `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,
    `exportedDayCount` y `lastSampleTimeMillis` para decidir qué días
    procesar y construir mensajes de estado.
  - Para cada día:
    - Carga registros mediante `StorageService.getLocationsBetween`.
    - Exporta a `glp-YYYYMMDD.zip` en Downloads usando
      `GeoJsonExporter` (GeoJSON) o `GpxExporter` (GPX) según
      `UploadOutputFormat`.
    - Marca la exportación local con `markExportedLocal`.
    - Resuelve el folderId efectivo combinando `DrivePrefs` (carpeta
      configurada en la UI) y `AppPrefs.folderId` como respaldo.
    - Crea un uploader (`UploadEngine.KOTLIN`) con `UploaderFactory`
      y sube el ZIP.
    - Registra el resultado con `markUploaded` o `markExportError` y
      actualiza `DrivePrefsRepository.backupStatus`.
    - Borra el ZIP siempre; si la subida tiene éxito y hay datos,
      borra también las filas de ese día con
      `StorageService.deleteLocations`.

### RealtimeUploadManager

- `RealtimeUploadManager` observa nuevas filas `LocationSample` y
  sube GeoJSON o GPX en función de los ajustes de Upload:
  - Observa `UploadPrefsRepository.scheduleFlow`,
    `intervalSecFlow`, `zoneIdFlow`, `outputFormatFlow`.
  - Observa `SettingsRepository.intervalSecFlow` y
    `SettingsRepository.drIntervalSecFlow` para conocer los
    intervalos de GPS / DR.
  - Se suscribe a `StorageService.latestFlow(limit = 1)` para detectar
    nuevas muestras.
  - Solo actúa cuando:
    - `UploadSchedule.REALTIME` está seleccionado, y
    - Drive está configurado (engine `KOTLIN` y folderId válido).
  - Aplica un cooldown basado en `intervalSec`:
    - `intervalSec == 0` o igual al intervalo de muestreo activo
      → se interpreta como “cada muestra E
  - Cuando debe subir:
    - Carga todas las muestras con `StorageService.getAllLocations`.
    - Genera, según `UploadOutputFormat`,
      - GeoJSON: `YYYYMMDD_HHmmss.json`
      - GPX   : `YYYYMMDD_HHmmss.gpx`
      en `cacheDir` usando la hora de la última muestra y `zoneId`.
    - Resuelve el folderId efectivo combinando `DrivePrefs` y
      `AppPrefs`.
    - Crea un uploader con `UploaderFactory.create(context, appPrefs.engine)`
      y sube el archivo generado.
    - Borra el archivo temporal.
    - Si la subida tiene éxito, borra las filas subidas con
      `StorageService.deleteLocations`.

Today preview (en `DriveSettingsScreen`) usa la misma zona horaria de
`UploadPrefs.zoneId` y genera un ZIP cuyo contenido (GeoJSON o GPX)
también está controlado por `UploadOutputFormat`. Los datos de Room
no se borran en modo preview.

---

## UI / Compose (resumen)

- `MainActivity`
  - `NavHost` a nivel de Activity con rutas `"home"`, `"drive_settings"`,
    `"upload_settings"`.
  - Solicita permisos y arranca `GeoLocationService` tras concederse.

- `AppRoot`
  - `NavHost` interno con rutas `"home"`, `"pickup"`, `"map"`.
  - AppBar:
    - Título (“GeoLocation E “Pickup E “Map E según ruta.
    - Botón atrás en Pickup y Map.
    - En home: botones `Map`, `Pickup`, `Drive`, `Upload` y
      `ServiceToggleAction`.

- `DriveSettingsScreen`
  - Selección de método de autenticación (Credential Manager /
    AppAuth), inicio/cierre de sesión, botón “Get token E ajustes de
    carpeta y acciones “Backup days before today E/ “Today preview E

- `UploadSettingsScreen`
  - Permite activar/desactivar Upload, elegir `UploadSchedule`
    (NONE/NIGHTLY/REALTIME), configurar `intervalSec`, `zoneId` y
    `outputFormat` (GeoJSON / GPX), y muestra avisos cuando Upload
    está activo sin Drive configurado.

---

## Superficie de API pública (librería)

- `:storageservice`
  - `StorageService`, `LocationSample`, `ExportedDay`,
    `SettingsRepository`
- `:dataselector`
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`,
    `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`
  - Autenticación / tokens:
    `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - Ajustes:
    `DrivePrefsRepository`, `UploadPrefsRepository`, `UploadSchedule`
  - API de Drive:
    `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - Exportación / subida:
    `UploadEngine`, `GeoJsonExporter`, `GpxExporter`, `Uploader`,
    `UploaderFactory`, `RealtimeUploadManager`
  - Trabajos en segundo plano:
    `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`
  - `GpsLocationEngine`, `GpsObservation`, `FusedLocationGpsEngine`

Otros tipos (DAOs, motores internos, helpers HTTP, etc.) deben
tratarse como detalles de implementación (`internal`) y pueden
cambiar sin aviso.

---

## Estilo de código (resumen)

- Todo código de producción (Kotlin / Java / XML / scripts Gradle)
  debe escribirse usando solo caracteres ASCII.
- El contenido en español / japonés se limita a archivos de
  documentación (`README_JA.md`, `README_ES.md`,
  `AGENTS_JA.md`, `AGENTS_ES.md`).
- Las APIs públicas se documentan con KDoc (`/** ... */`) y los
  detalles internos usan comentarios `//` sencillos y encabezados
  consistentes (`// ---- Section ----`). 
