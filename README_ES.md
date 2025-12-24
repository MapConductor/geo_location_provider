# GeoLocationProvider (versión en español)

GeoLocationProvider es un SDK y una aplicación de ejemplo para
**registrar, almacenar, exportar y subir datos de localización** en
Android.

Registra localización en segundo plano en una base de datos Room,
exporta los datos como GeoJSON+ZIP (o GPX+ZIP) y puede subirlos a **Google Drive**
tanto mediante copias de seguridad nocturnas programadas como mediante
subida en tiempo real opcional.

---

## Funcionalidades

- Obtención de localización en segundo plano  
  (intervalos de GPS y Dead Reckoning configurables).
- Dead Reckoning con modos configurables (Predicción / Relleno).
- Almacenamiento en base de datos Room (`LocationSample`,
  `ExportedDay`).
- Exportación a formato GeoJSON o GPX con compresión ZIP opcional.
- Copia de seguridad diaria a medianoche (`MidnightExportWorker`).
- Exportación manual para “Backup days before today” y
  “Today preview”.
- Función Pickup (muestras representativas según periodo / número de
  puntos).
- Visualización en mapa de trazas de GPS y Dead Reckoning.
- Subida a Google Drive con selección de carpeta y diagnósticos de
  autenticación.
- Gestor de subida en tiempo real con ajuste de intervalo y zona
  horaria.

---

## Arquitectura general (resumen)

### Módulos

- `:app` – Aplicación de ejemplo basada en Jetpack Compose  
  (historial, Pickup, mapa, ajustes de Drive, ajustes de Upload,
  copias de seguridad manuales).
- `:core` – Servicio en primer plano `GeoLocationService`, gestión de
  sensores y configuración compartida (`UploadEngine`). Persiste en
  `:storageservice`, usa `:gps` y `:deadreckoning`.
- `:gps` – Abstracción de GPS (`GpsLocationEngine`,
  `GpsObservation`, `FusedLocationGpsEngine`).
- `:storageservice` – `AppDatabase` de Room, DAOs y fachada
  `StorageService`.
- `:dataselector` – Lógica de selección para Pickup.
- `:datamanager` – Exportación a GeoJSON, compresión ZIP,
  `MidnightExportWorker` / `MidnightExportScheduler`,
  `RealtimeUploadManager`, repositorios de preferencias de
  Drive/Upload.
- `:deadreckoning` – Motor y API pública de Dead Reckoning.
- `:auth-appauth` / `:auth-credentialmanager` – Implementaciones de
  `GoogleDriveTokenProvider`.

---

## Entidades y almacenamiento

- `LocationSample` – Muestra de localización (latitud, longitud,
  precisión, velocidad, estado de batería, calidad GNSS, etc.).
- `ExportedDay` – Estado de exportación por día (exportado local,
  resultado de subida, último error).
- `StorageService` – Fachada única alrededor de Room:
  - `latestFlow(ctx, limit)` – Flow con las `limit` muestras más
    recientes (orden descendente por `timeMillis`).
  - `getAllLocations(ctx)` – Todas las muestras en orden ascendente
    por `timeMillis`.
  - `getLocationsBetween(ctx, from, to, softLimit)` – Muestras en el
    intervalo semiabierto `[from, to)` ordenadas por `timeMillis`.
  - `ensureExportedDay`, `markExportedLocal`, `markUploaded`,
    `markExportError` – Gestión de `ExportedDay`.

---

## Exportación y subida

### Copia diaria (MidnightExportWorker)

1. Lee `UploadPrefs.zoneId` para determinar la zona horaria y define
   el rango diario `[00:00, 24:00)`.
2. Carga muestras del día con `StorageService.getLocationsBetween`.
3. Exporta a `glp-YYYYMMDD.zip` en Downloads usando `GeoJsonExporter`
   (GeoJSON) o `GpxExporter` (GPX) según `UploadOutputFormat`.
4. Resuelve el folderId efectivo combinando `DrivePrefs` y `AppPrefs`.
5. Crea un uploader con `UploaderFactory.create(..., UploadEngine.KOTLIN)` y sube el ZIP.
6. Registra el resultado en `ExportedDay` (`markUploaded` /
   `markExportError`) y borra siempre el ZIP.
7. Si la subida tiene éxito y hay datos, borra las filas de ese día
   con `StorageService.deleteLocations`.

### RealtimeUploadManager

- Observa:
  - `UploadPrefsRepository.scheduleFlow`, `intervalSecFlow`,
    `zoneIdFlow`, `outputFormatFlow`.
  - `SettingsRepository.intervalSecFlow` y `drIntervalSecFlow`.
  - `StorageService.latestFlow(limit = 1)` para detectar nuevas
    muestras.
- Solo actúa cuando:
  - `UploadSchedule.REALTIME` está seleccionado, y
  - Drive está configurado (engine `KOTLIN` y folderId válido).
- Cuando toca subir:
  - Carga todas las muestras con `StorageService.getAllLocations`.
  - Genera, según `UploadOutputFormat`,
    - GeoJSON: `YYYYMMDD_HHmmss.json`
    - GPX   : `YYYYMMDD_HHmmss.gpx`
    en `cacheDir` usando la hora de la última muestra y `zoneId`.
  - Resuelve el folderId efectivo desde `DrivePrefs` y `AppPrefs`.
  - Crea un uploader con `UploaderFactory.create(context, appPrefs.engine)` y sube el archivo generado.
  - Borra el archivo temporal y, si la subida tiene éxito, borra las
    filas subidas con `StorageService.deleteLocations`.

---

## Autenticación y DriveTokenProviderRegistry

- Todas las subidas a Drive usan `GoogleDriveTokenProvider`:
  - Devuelve tokens sin prefijo `"Bearer "`.
  - Devuelve `null` en errores normales (sin lanzar excepciones).
  - No debe iniciar UI desde segundo plano.

### Opciones de autenticación

- Credential Manager (`:auth-credentialmanager`):
  - Usa `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` como client ID de
    servidor/web.
- AppAuth (`:auth-appauth`):
  - Usa `APPAUTH_CLIENT_ID` como client ID de aplicación instalada y
    el redirect
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect`.

### DriveTokenProviderRegistry

- `DriveTokenProviderRegistry` mantiene un proveedor de tokens de
  fondo utilizado por:
  - `MidnightExportWorker` / `MidnightExportScheduler`.
  - `RealtimeUploadManager` (cuando `UploaderFactory.create` se llama
    sin `tokenProvider`).
- En la app de ejemplo:
  - `App.onCreate()` registra inicialmente `CredentialManagerAuth.get(this)`
    como proveedor por defecto.
  - Después lee `DrivePrefs.authMethod` y, si es `"appauth"` y AppAuth
    ya está autenticado, cambia el proveedor de fondo a
    `AppAuthAuth.get(this)`.
  - Siempre llama a `MidnightExportScheduler.scheduleNext(this)` y
    `RealtimeUploadManager.start(this)`.
- `DriveSettingsViewModel`:
  - `markCredentialManagerSignedIn()` guarda
    `authMethod = "credential_manager"` y `accountEmail = "cm_signed_in"`
    (si estaba vacío) y registra `CredentialManagerAuth.get(app)` en
    el registro.
  - `markAppAuthSignedIn()` guarda `authMethod = "appauth"` y
    `accountEmail = "appauth_signed_in"` (si estaba vacío) y registra
    `AppAuthAuth.get(app)` como proveedor de fondo.
- `UploadSettingsViewModel` considera que Drive está configurado
  cuando `DrivePrefs.accountEmail` no está vacío; solo entonces
  permite activar la subida automática.

---

## UI / Compose

- `MainActivity`
  - `NavHost` con rutas `"home"`, `"drive_settings"`,
    `"upload_settings"`.
  - Solicita permisos y arranca `GeoLocationService` tras concederse.

- `AppRoot`
  - `NavHost` interno con rutas `"home"`, `"pickup"`, `"map"`.
  - AppBar:
    - Título (“GeoLocation”, “Pickup”, “Map”) según la ruta.
    - Botón atrás en Pickup y Map.
    - En home: botones `Map`, `Pickup`, `Drive`, `Upload` y
      `ServiceToggleAction`.

- `GeoLocationProviderScreen`
  - Controles de intervalo (GPS/DR) y lista de historial.
  - `HistoryViewModel` mantiene un buffer en memoria de hasta 9
    `LocationSample` recientes, construido a partir de
    `StorageService.latestFlow(limit = 9)` y ordenado por
    `timeMillis` descendente。  
    El buffer está desacoplado de los borrados de Room, de modo que la
    lista de historial no “parpadea” cuando se borran filas tras
    subir datos correctamente.

- `PickupScreen`
  - Usa `SelectorUseCases` para aplicar condiciones de Pickup y
    mostrar resultados.

- `MapScreen` / `GoogleMapsExample`
  - Usa `GoogleMapsView` de MapConductor con backend de Google Maps
    para dibujar polilíneas de GPS/DR y una superposición de
    depuración.

- `DriveSettingsScreen`
  - Selección de método de autenticación (Credential Manager /
    AppAuth), inicio/cierre de sesión, botón “Get token”, ajustes de
    carpeta y acciones “Backup days before today” / “Today preview”.

- `UploadSettingsScreen`
  - Permite activar/desactivar Upload, elegir `UploadSchedule`
    (NONE/NIGHTLY/REALTIME), configurar `intervalSec` y `zoneId`, y
    muestra avisos cuando Upload está activado sin Drive configurado.

---

## Superficie de API pública (resumen)

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
    `UploadEngine`, `GeoJsonExporter`, `Uploader`, `UploaderFactory`,
    `RealtimeUploadManager`
  - Trabajos en segundo plano:
    `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`
  - `GpsLocationEngine`, `GpsObservation`, `FusedLocationGpsEngine`

---

## Notas de desarrollo

- El proyecto se basa en Kotlin + Jetpack Compose + Room + DataStore +
  WorkManager.
- Todo el **código de producción** (Kotlin / Java / XML / scripts
  Gradle) debe escribirse usando solo caracteres ASCII; el contenido
  en español / japonés se limita a archivos `*.md`.
- Cuando cambies la integración con Drive, asegúrate de que los
  scopes OAuth y las redirect URIs de los README (EN/JA/ES) coinciden
  con la configuración en Google Cloud Console。
