# :datamanager (Exportación + Upload + Integración con Drive)

Módulo Gradle: `:datamanager`

Este módulo se encarga de exportar datos (GeoJSON / GPX), programar trabajo en segundo plano (exportación nocturna y subida en tiempo real) y subir a Google Drive usando un proveedor de tokens intercambiable.

## Contrato de frontera (configuras -> pasa esto)

Configuras:

- Auth Drive: `GoogleDriveTokenProvider` + `DriveTokenProviderRegistry`.
- Preferencias: `DrivePrefsRepository` y `UploadPrefsRepository`.
- Puntos de inicio: `MidnightExportScheduler.scheduleNext(...)` y `RealtimeUploadManager.start(...)`.

Efectos:

- Nightly:
  - export por día a Downloads/`GeoLocationProvider` como ZIP
  - upload a la carpeta Drive
  - estado por día en `:storageservice`
  - borra ZIP temporal
  - si upload OK, borra filas del día (drain)
- Realtime:
  - genera snapshot en `cacheDir`
  - sube según intervalo
  - borra caché
  - si OK, borra filas subidas (drain)

## Reglas (detalles importantes)

- Gate de schedule:
  - `RealtimeUploadManager` solo actúa cuando `schedule == REALTIME`.
  - `MidnightExportWorker` puede saltar si el schedule no es nightly (salvo modo force/runNow).
- Resolución de folder:
  - primero `DrivePrefsRepository.folderIdFlow` (UI/DataStore)
  - luego `AppPrefs.snapshot(context).folderId` (legacy)
- Engine:
  - solo crea uploader cuando `AppPrefs.snapshot(context).engine == UploadEngine.KOTLIN`.
- Nombres:
  - Nightly: `glp-YYYYMMDD.zip` en Downloads/`GeoLocationProvider`.
  - Realtime: `YYYYMMDD_HHmmss.json` o `.gpx` en `cacheDir` (se borra después).

## Recetas

### Nightly backup (ZIP por día)

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)

val drivePrefs = DrivePrefsRepository(appContext)
drivePrefs.setFolderId("YOUR_DRIVE_FOLDER_ID")

val uploadPrefs = UploadPrefsRepository(appContext)
uploadPrefs.setSchedule(UploadSchedule.NIGHTLY)
uploadPrefs.setOutputFormat(UploadOutputFormat.GEOJSON)
uploadPrefs.setZoneId("Asia/Tokyo")

MidnightExportScheduler.scheduleNext(appContext)
```

### Run backlog now

```kotlin
MidnightExportWorker.runNow(appContext)
```

### Realtime upload (snapshot + drain)

```kotlin
val uploadPrefs = UploadPrefsRepository(appContext)
uploadPrefs.setSchedule(UploadSchedule.REALTIME)
uploadPrefs.setIntervalSec(60)

RealtimeUploadManager.start(appContext)
```

## Wiring mínimo end-to-end (código -> resultados visibles)

Si configuras lo siguiente, deberías ver export/upload funcionando sin leer código interno.

### 1) Registrar proveedor de token en background (auth -> Drive)

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

- Si el provider retorna `null`, el upload se omite y se trata como "no autorizado aún".

### 2) Habilitar UploadEngine (engine -> crear uploader)

```kotlin
AppPrefs.saveEngine(context, UploadEngine.KOTLIN)
```

- Si `engine != KOTLIN`, el upload se omite por diseño.

### 3) Configurar carpeta Drive (folder id -> destino)

Preferido:

```kotlin
DrivePrefsRepository(context).setFolderId("YOUR_DRIVE_FOLDER_ID")
```

Fallback legacy:

```kotlin
AppPrefs.saveFolderId(context, "YOUR_DRIVE_FOLDER_ID_OR_URL")
```

- Si el folder id es vacío, el upload se omite.

### 4) Elegir schedule (schedule -> cuando corre)

- Nightly:
  - `UploadPrefsRepository(context).setSchedule(UploadSchedule.NIGHTLY)`
  - llamar `MidnightExportScheduler.scheduleNext(context)`
- Realtime:
  - `UploadPrefsRepository(context).setSchedule(UploadSchedule.REALTIME)`
  - llamar `RealtimeUploadManager.start(context)`

## Troubleshooting (síntoma -> causa probable)

- "No se sube nada":
  - provider no registrado, o `getAccessToken()` retorna `null`
  - folder id vacío (DrivePrefs/AppPrefs)
  - `AppPrefs.engine != UploadEngine.KOTLIN`
  - schedule `NONE`, o no se llamó scheduler/manager
- "Nightly no corre":
  - no se llamó `MidnightExportScheduler.scheduleNext(context)` o hay límites de background del sistema
- "Realtime no reacciona":
  - no se llamó `RealtimeUploadManager.start(context)` o schedule no es `REALTIME`
- "No se borran filas":
  - borrado solo después de upload exitoso (upload-as-drain)

## Notas

- Workers no deben iniciar UI. Si se requiere auth, el provider devuelve `null`.
- Rangos usan `[from, to)` y zona horaria vía preferencias.
- Borra archivos temporales después de cada intento.
