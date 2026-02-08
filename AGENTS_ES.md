# Repository Guidelines (espanol)

Este archivo resume las guias del repositorio GeoLocationProvider en espanol.
La version canonica y mas actualizada es `AGENTS.md` (ingles).

Si cambias comportamiento entre modulos, actualiza primero `AGENTS.md` y luego los README del modulo correspondiente.

---

## Mapa de documentacion

- Overviews en la raiz:
  - `README.md` (EN)
  - `README_JA.md` (JA)
  - `README_ES.md` (ES)
- Docs por modulo (recomendado para uso en fronteras):
  - Cada modulo contiene:
    - `README.md` (EN)
    - `README_JP.md` (JP)
    - `README_ES.md` (ES)

---

## Estructura del proyecto y modulos

Proyecto Gradle raiz: `GeoLocationProvider`

Modulos:

- `:app`: app de ejemplo Jetpack Compose (history, pickup, map, drive settings, upload settings).
- `:core`: servicio en primer plano `GeoLocationService` y orquestacion (GPS, correccion opcional, dead reckoning opcional, persistencia).
- `:gps`: abstraccion GPS (`GpsLocationEngine`, `GpsObservation`) y engine por defecto (`LocationManagerGpsEngine`).
- `:storageservice`: Room (DAOs internos) + facade `StorageService`. Tambien `SettingsRepository` (DataStore).
- `:dataselector`: seleccion/pickup via `LocationSampleSource` y `SelectorCondition` (sin dependencia de Room).
- `:datamanager`: export (GeoJSON/GPX, ZIP opcional), upload a Google Drive, y scheduling en background.
- `:deadreckoning`: motor DR basado en IMU y API publica.
- `:auth-appauth`: `GoogleDriveTokenProvider` via AppAuth.
- `:auth-credentialmanager`: `GoogleDriveTokenProvider` via Credential Manager + Identity.
- `mapconductor-core-src`: fuentes vendorizadas de MapConductor usadas solo por `:app` (tratar como read-only).

Dependencias (alto nivel):

- `:app` -> `:core`, `:dataselector`, `:datamanager`, `:storageservice`, `:deadreckoning`, `:gps`, modulos auth
- `:core` -> `:gps`, `:storageservice`, `:deadreckoning`
- `:datamanager` -> `:core`, `:storageservice`
- `:dataselector` -> `LocationSampleSource` (y el modelo compartido `LocationSample`); la implementacion vive en `:app` delegando a `StorageService`

Config local (no se versiona):

- `local.properties`: rutas SDK y settings de la maquina
- `secrets.properties`: valores del Secrets Gradle Plugin (`local.default.properties` como plantilla)

---

## Build y tests

Desde la raiz:

- Build: `./gradlew :app:assembleDebug`
- Install: `./gradlew :app:installDebug`
- Lint: `./gradlew lint`
- Unit tests: `./gradlew test`
- Android tests: `./gradlew :app:connectedAndroidTest`

---

## Estilo, naming y encoding

- Indent de 4 espacios.
- Quita imports sin usar y codigo muerto.

Regla ASCII-only (codigo de produccion):

- Archivos de produccion (Kotlin/Java/XML/Gradle) deben ser ASCII-only.
- Texto multilingue se permite solo en docs (`*.md`).

---

## Responsabilidades y fronteras de modulos

### StorageService / Room (`:storageservice`)

Reglas:

- Solo `:storageservice` toca DAOs de Room directamente.
- Otros modulos usan `StorageService`.

Contratos clave:

- `latestFlow(ctx, limit)`: newest-first (`timeMillis` descendente)
- `getLocationsBetween(ctx, from, to, softLimit)`: rango semiabierto `[from, to)` ascendente
- `deleteLocations(ctx, items)`: no-op si lista vacia

### dataselector / Pickup (`:dataselector`)

- Depende solo de `LocationSampleSource` (y tipos de modelo compartidos como `LocationSample`).
- No conoce Room/DAOs ni `StorageService`.
- Semantica de rango: `[from, to)`.
- `intervalSec == null`: extraccion directa.
- `intervalSec != null`: grilla, gaps como `SelectedSlot(sample = null)`.

### GeoLocationService / GPS / DR (`:core`, `:gps`, `:deadreckoning`)

`GeoLocationService` orquesta:

- Observaciones GPS via `GpsLocationEngine` (actualmente `LocationManagerGpsEngine`).
- Correccion opcional via `GpsCorrectionEngineRegistry`.
- Dead reckoning opcional via `:deadreckoning`.
- Persistencia via `StorageService`.

Providers en `LocationSample.provider`:

- `"gps"`: GPS raw
- `"gps_corrected"`: corregido (solo si esta habilitado y difiere)
- `"dead_reckoning"`: muestras DR

Modos DR:

- `DrMode.Prediction`: ticker inserta puntos realtime `dead_reckoning`.
- `DrMode.Completion`: backfill entre fixes GPS (sin ticker realtime).

### SettingsRepository (DataStore)

- `intervalSecFlow`: intervalo GPS (seg)
- `drIntervalSecFlow`: intervalo DR (seg)
  - `0` deshabilita DR
  - `> 0` clamp a minimo 1
- `drGpsIntervalSecFlow`: throttle GPS->DR (0 cada fix)
- `drModeFlow`: Prediction vs Completion

El servicio observa estos flows y actualiza comportamiento en vivo.

---

## Auth y Drive

Contrato de `GoogleDriveTokenProvider`:

- Retorna access tokens "bare" (sin "Bearer ").
- Fallos normales retornan `null` (no excepciones).
- Background nunca inicia UI. Si hace falta UI, retorna `null` y la app lo maneja en foreground.

Registro:

- `DriveTokenProviderRegistry` guarda un provider en memoria para que `:datamanager` lo use en background.

---

## Export y upload (`:datamanager`)

Nightly (backlog hasta ayer):

- `MidnightExportScheduler` / `MidnightExportWorker`
- Zona horaria: `UploadPrefsRepository.zoneId` (default `Asia/Tokyo`)
- Rango diario: `[00:00, 24:00)` (semiabierto)
- Borrar archivos temporales despues de cada intento
- Borrar filas de BD solo cuando el upload fue OK

Realtime (snapshot + drain):

- `RealtimeUploadManager`
- Observa `StorageService.latestFlow(limit = 1)` cuando schedule es `REALTIME`
- En exito: borra filas subidas (upload-as-drain)

Reglas de resolucion:

- Folder id: primero `DrivePrefsRepository`, fallback `AppPrefs` (legacy)
- Engine: upload solo si `AppPrefs.engine == UploadEngine.KOTLIN`

---

## UI / Compose (`:app`)

La app de ejemplo es una referencia de host:

- Start/stop de `GeoLocationService`.
- Escribe settings via `SettingsRepository`.
- Lee history via `StorageService.latestFlow(...)`.
- Corre auth flows y registra background provider.
- Configura export/upload via `DrivePrefsRepository` y `UploadPrefsRepository`.

Labels de provider en Map:

- GPS: `provider="gps"`
- GPS(EKF): `provider="gps_corrected"`
- Dead Reckoning: `provider="dead_reckoning"`

---

## Seguridad y OAuth

No comitear:

- `local.properties`, `secrets.properties`, `google-services.json`, etc.

Separar client IDs:

- Credential Manager: `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (web/server)
- AppAuth: `APPAUTH_CLIENT_ID` (installed app + custom scheme redirect)

---

## API publica (superficie estable)

Tipos previstos como estables:

- `:storageservice`: `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`: `SelectorCondition`, `SortOrder`, `SelectedSlot`, `LocationSampleSource`, `SelectorRepository`
- `:datamanager`: `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`, `DrivePrefsRepository`, `UploadPrefsRepository`, `UploadSchedule`, `Uploader`, `UploaderFactory`, `GeoJsonExporter`, `RealtimeUploadManager`, `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`: `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`: `GpsLocationEngine`, `GpsObservation`
