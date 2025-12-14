# Repository Guidelines (resumen en español)

Este documento resume en español la **estructura por módulos, la
separación de responsabilidades y las políticas de código** del
repositorio GeoLocationProvider.  
La referencia completa y siempre actualizada es `AGENTS.md` (inglés).

---

## Estructura del proyecto y módulos

- Proyecto Gradle raíz `GeoLocationProvider`:
  - `:app` – Aplicación de ejemplo basada en Jetpack Compose  
    (lista de historial, pantalla de Pickup, pantalla de mapa,
    ajustes de Drive, ajustes de Upload, copia manual).
  - `:core` – Servicio en primer plano `GeoLocationService`,
    gestión de sensores del dispositivo y configuración compartida
    (`UploadEngine`). Persiste en `:storageservice`, usa `:gps` y
    `:deadreckoning`.
  - `:gps` – Abstracción de GPS (`GpsLocationEngine`,
    `GpsObservation`, `FusedLocationGpsEngine`) que envuelve
    `FusedLocationProviderClient` y `GnssStatus`.
  - `:storageservice` – `AppDatabase` de Room, DAOs y fachada
    `StorageService`. Gestiona `LocationSample` (historial) y
    `ExportedDay` (estado de exportación por día).
  - `:dataselector` – Lógica de selección para Pickup sobre la
    abstracción `LocationSampleSource`.
  - `:datamanager` – Exportación GeoJSON, compresión ZIP,
    `MidnightExportWorker` / `MidnightExportScheduler`,
    `RealtimeUploadManager`, cliente HTTP de Drive y repositorios de
    preferencias de Drive/Upload.
  - `:deadreckoning` – Motor y API de Dead Reckoning
    (`DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`).
  - `:auth-appauth` / `:auth-credentialmanager` – Implementaciones de
    `GoogleDriveTokenProvider`.
  - `mapconductor-core-src` – Código fuente de MapConductor
    vendorizado, usado solo desde `:app` (tratar como solo lectura).

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

- `latestFlow(ctx, limit)` – Flow con las `limit` muestras más
  recientes de `LocationSample` (orden descendente por `timeMillis`).
- `getAllLocations(ctx)` – Todas las muestras en orden ascendente por
  `timeMillis` (solo para conjuntos pequeños: vista previa, snapshot).
- `getLocationsBetween(ctx, from, to, softLimit)` – Muestras en el
  intervalo semiabierto `[from, to)` ordenadas por `timeMillis`.
- `insertLocation`, `deleteLocations` – Inserción/borrado de filas
  con trazas `DB/TRACE`.
- `lastSampleTimeMillis`, `ensureExportedDay`, `oldestNotUploadedDay`,
  `nextNotUploadedDayAfter`, `exportedDayCount`, `markExportedLocal`,
  `markUploaded`, `markExportError` – Gestión del estado por día en
  `ExportedDay`.

Todos los accesos a BD se ejecutan en `Dispatchers.IO` dentro de
`StorageService`.

---

## dataselector / Pickup

- `:dataselector` solo depende de `LocationSampleSource` y no conoce
  Room ni `StorageService`.
- `SelectorRepository`:
  - `intervalSec == null` → extracción directa (sin rejilla).
  - `intervalSec != null` → rejilla temporal, selección de una
    muestra representativa por ventana, huecos representados por
    `SelectedSlot(sample = null)`.

Pickup UI (`PickupScreen`) usa `SelectorUseCases` para acceder a este
módulo.

---

## GeoLocationService / GPS / Dead Reckoning

- `GeoLocationService`:
  - Usa `GpsLocationEngine` para obtener `GpsObservation`, las
    transforma en filas `LocationSample` (provider `"gps"`) y las
    guarda vía `StorageService`.
  - Mantiene una posición “GPS hold” filtrada que se envía a
    `DeadReckoning` como `GpsFix`.
  - Ejecuta un ticker de Dead Reckoning controlado por
    `SettingsRepository.drIntervalSecFlow` y escribe muestras
    `LocationSample` con provider `"dead_reckoning"`.

El módulo `:deadreckoning` mantiene un estado 1D alineado con la
dirección GPS, usa acelerómetro y velocidad para detectar estático /
movimiento y limita saltos físicamente imposibles.

---

## Ajustes de sampling y subida

- `SettingsRepository` (DataStore en `:storageservice`):
  - `intervalSecFlow(context)` – intervalo de GPS en segundos.
  - `drIntervalSecFlow(context)` – intervalo de Dead Reckoning
    (`0` = DR desactivado).

- `UploadPrefsRepository` (DataStore en `:datamanager`):
  - `scheduleFlow` – `UploadSchedule.NONE` / `NIGHTLY` /
    `REALTIME`.
  - `intervalSecFlow` – intervalo de subida en segundos (0 o
    1–86400).
  - `zoneIdFlow` – ID de zona horaria IANA.

`UploadSettingsViewModel` publica estos valores a la pantalla de
ajustes de Upload y solo permite activar la subida si Drive está
configurado (hay `accountEmail` en `DrivePrefs`).

---

## Auth / Drive / DriveTokenProviderRegistry

- `GoogleDriveTokenProvider`:
  - Devuelve tokens de acceso sin prefijo `"Bearer "`.
  - Devuelve `null` en errores normales (sin lanzar excepciones).
  - No debe iniciar UI desde segundo plano.

- Implementaciones recomendadas:
  - `CredentialManagerTokenProvider` (`:auth-credentialmanager`)
  - `AppAuthTokenProvider` (`:auth-appauth`)

### DriveTokenProviderRegistry (segundo plano)

- `DriveTokenProviderRegistry` mantiene un `GoogleDriveTokenProvider`
  de uso global que consumen:
  - `MidnightExportWorker` / `MidnightExportScheduler`
  - `RealtimeUploadManager`
  a través de `UploaderFactory.create(context, engine, tokenProvider = null)`.

- En la app de ejemplo, `App.onCreate()`:
  - Registra inicialmente `CredentialManagerAuth.get(this)` como
    proveedor por defecto.
  - Lee `DrivePrefs.authMethod` y, si es `"appauth"` y AppAuth ya
    está autenticado, cambia el proveedor de fondo a
    `AppAuthAuth.get(this)`.
  - Llama siempre a `MidnightExportScheduler.scheduleNext(this)` y
    `RealtimeUploadManager.start(this)`.

- `DriveSettingsViewModel` sincroniza preferencias y registro:
  - `markCredentialManagerSignedIn()`:
    - Guarda `authMethod = "credential_manager"`.
    - Si `accountEmail` está vacío, guarda `"cm_signed_in"`.
    - Registra `CredentialManagerAuth.get(app)` en la registry.
  - `markAppAuthSignedIn()`:
    - Guarda `authMethod = "appauth"`.
    - Si `accountEmail` está vacío, guarda `"appauth_signed_in"`.
    - Registra `AppAuthAuth.get(app)` como proveedor de fondo.
- `UploadSettingsViewModel` considera que Drive está configurado
  cuando `DrivePrefs.accountEmail` no está vacío y solo entonces
  permite activar la subida automática.

---

## Exportación y subida

### MidnightExportWorker / MidnightExportScheduler

- Usa `UploadPrefs.zoneId` para determinar la zona horaria y procesa
  días `[00:00, 24:00)`:
  - Carga registros mediante `StorageService.getLocationsBetween`.
  - Exporta a GeoJSON+ZIP con `GeoJsonExporter`.
  - Marca exportación local con `markExportedLocal`.
  - Resuelve folderId combinando `DrivePrefs` y `AppPrefs`.
  - Crea un uploader (`UploadEngine.KOTLIN`) con `UploaderFactory`.
  - Sube el ZIP y llama a `markUploaded` o `markExportError`.
  - Borra el ZIP y, si la subida tiene éxito y hay datos, borra las
    filas del día con `StorageService.deleteLocations`.

### RealtimeUploadManager / ajustes de Upload

- Observa:
  - `UploadPrefsRepository.scheduleFlow`, `intervalSecFlow`,
    `zoneIdFlow`.
  - `SettingsRepository.intervalSecFlow`, `drIntervalSecFlow`.
  - `StorageService.latestFlow(limit = 1)` para detectar nuevas
    muestras.
- Solo actúa cuando `UploadSchedule.REALTIME` está activo y Drive
  está configurado.
- Cuando debe subir:
  - Carga todas las muestras con `StorageService.getAllLocations`.
  - Genera `YYYYMMDD_HHmmss.json` en `cacheDir` a partir de la hora
    de la última muestra y `zoneId`.
  - Resuelve el folderId efectivo desde `DrivePrefs` y `AppPrefs`.
  - Crea un uploader con `UploaderFactory.create(context, appPrefs.engine)` y sube el JSON.
  - Borra el archivo temporal y, si la subida tiene éxito, borra las
    filas subidas con `StorageService.deleteLocations`.

---

## UI / Compose (resumen)

- `MainActivity`
  - `NavHost` a nivel de Activity con rutas `"home"`, `"drive_settings"`,
    `"upload_settings"`.
  - Solicita permisos y arranca `GeoLocationService` tras concederse.

- `AppRoot`
  - `NavHost` interno con rutas `"home"`, `"pickup"`, `"map"`.
  - AppBar:
    - Título (“GeoLocation”, “Pickup”, “Map”) según ruta.
    - Botón atrás en Pickup y Map.
    - En home: botones `Map`, `Pickup`, `Drive`, `Upload` y
      `ServiceToggleAction`.

- `GeoLocationProviderScreen`
  - Muestra controles de intervalo (GPS / DR) y la lista de historial.
  - `HistoryViewModel` mantiene un buffer en memoria de hasta 9
    muestras recientes (`LocationSample`) construido a partir de
    `StorageService.latestFlow(limit = 9)` y ordenado por
    `timeMillis` descendente. El buffer está desacoplado de los
    borrados de Room, por lo que la lista de historial no “parpadea”
    cuando los workers borran filas tras subidas correctas; las
    muestras se eliminan solo al salir del buffer por antigüedad.

- `PickupScreen`
  - Usa `SelectorUseCases` para aplicar condiciones y mostrar
    resultados de Pickup.

- `MapScreen` / `GoogleMapsExample`
  - Usa `GoogleMapsView` de MapConductor con backend de Google Maps
    para dibujar polilíneas de GPS/DR y una superposición de
    depuración.

- `DriveSettingsScreen`
  - Selección de método de autenticación (Credential Manager / AppAuth),
    inicio/cierre de sesión, botón “Get token”, ajustes de carpeta y
    acciones “Backup days before today” / “Today preview”.

- `UploadSettingsScreen`
  - Permite activar/desactivar Upload, elegir `UploadSchedule`
    (NONE/NIGHTLY/REALTIME), configurar `intervalSec` y `zoneId`, y
    muestra avisos cuando Upload está activo sin Drive configurado.

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
    `UploadEngine`, `GeoJsonExporter`, `Uploader`, `UploaderFactory`,
    `RealtimeUploadManager`
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

