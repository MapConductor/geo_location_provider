# Repository Guidelines (resumen en español)

Este documento resume en español las **políticas, la estructura por
capas y la separación de responsabilidades** del repositorio
GeoLocationProvider.

La referencia completa y siempre actualizada es la versión inglesa
`AGENTS.md`.  
Cuando cambies diseño o comportamiento, consulta primero `AGENTS.md` y
actualiza este resumen si es necesario.

---

## Estructura del proyecto y módulos

El proyecto Gradle raíz `GeoLocationProvider` se compone
principalmente de los siguientes módulos:

- `:app` – Aplicación de ejemplo basada en Jetpack Compose  
  (lista de historial, pantalla de Pickup, pantalla de Mapa, ajustes
  de Drive, ajustes de Upload, exportación manual, etc.).
- `:core` – Servicio en primer plano `GeoLocationService`, gestión de
  sensores del dispositivo y configuración compartida (por ejemplo
  `UploadEngine`). La persistencia se delega en `:storageservice`, la
  GNSS en `:gps` y Dead Reckoning en `:deadreckoning`.
- `:gps` – Capa de abstracción de GPS:
  `GpsLocationEngine`, `GpsObservation`, `FusedLocationGpsEngine`.  
  Envuelve `FusedLocationProviderClient` y `GnssStatus` y expone un
  modelo de dominio estable para `GeoLocationService`.
- `:storageservice` – `AppDatabase` de Room, DAOs y fachada
  `StorageService`.  
  Gestiona de forma centralizada los registros de localización y el
  estado de exportación.
- `:dataselector` – Lógica de selección para Pickup. Filtra
  `LocationSample` a través de la abstracción `LocationSampleSource`
  y construye filas representativas `SelectedSlot`.
- `:datamanager` – Exportación a GeoJSON, compresión ZIP,
  `MidnightExportWorker` / `MidnightExportScheduler`,
  `RealtimeUploadManager`, cliente HTTP de Drive y repositorios de
  preferencias de Drive/Upload.
- `:deadreckoning` – Motor y API pública de Dead Reckoning
  (`DeadReckoning`, `GpsFix`, `PredictedPoint`,
  `DeadReckoningConfig`, `DeadReckoningFactory`).
- `:auth-appauth` / `:auth-credentialmanager` – Módulos de
  autenticación que proporcionan implementaciones de
  `GoogleDriveTokenProvider`.
- `mapconductor-core-src` – Código fuente de MapConductor incluido en
  el repositorio (vendorizado).  
  Por defecto se considera **solo lectura**; los cambios deberían
  idealmente hacerse upstream.

Dirección aproximada de dependencias:

- `:app` → `:core`, `:dataselector`, `:datamanager`,
  `:storageservice`, `:deadreckoning`, `:gps`, módulos de auth y
  MapConductor.
- `:core` → `:gps`, `:storageservice`, `:deadreckoning`.
- `:datamanager` → `:core`, `:storageservice`, integración con
  Drive.
- `:dataselector` → solo la abstracción `LocationSampleSource`  
  (la implementación concreta vive en `:app`, envolviendo
  `StorageService`).

---

## StorageService / Acceso a Room

- Todo acceso a `AppDatabase` / DAOs desde fuera de
  `:storageservice` debe hacerse a través de `StorageService`.
  - Dentro de `:storageservice` se permite usar DAOs directamente.
  - No importes DAOs desde `:app`, `:core`, `:datamanager` ni
    `:dataselector`.

API clave de `StorageService` (resumen):

- `latestFlow(ctx, limit)` – Flow con las últimas `limit` filas de
  `LocationSample` ordenadas de más reciente a más antigua.  
  Lo usan las pantallas de historial, el mapa y el gestor de subida
  en tiempo real.
- `getAllLocations(ctx)` – Todas las filas de `LocationSample` en
  orden ascendente por `timeMillis`.  
  Úsalo solo para conjuntos pequeños (vista previa de hoy, snapshot
  puntual, debug).
- `getLocationsBetween(ctx, from, to, softLimit)` – Muestras en el
  intervalo semiabierto `[from, to)` ordenadas por `timeMillis` de
  forma ascendente.  
  Se usa para exportación diaria y extracción por rango de tiempo
  (Pickup).
- `insertLocation(ctx, sample)` – Inserta una muestra y escribe
  trazas `DB/TRACE` con el contador antes/después.
- `deleteLocations(ctx, items)` – Elimina en bloque; si la lista está
  vacía, no toca la base de datos.
- `lastSampleTimeMillis(ctx)` – Máximo `timeMillis` entre todas las
  muestras, o `null` si la tabla está vacía.  
  Sirve para determinar el último día que puede necesitar copia de
  seguridad.
- `ensureExportedDay`, `oldestNotUploadedDay`,
  `nextNotUploadedDayAfter`, `exportedDayCount`, `markExportedLocal`,
  `markUploaded`, `markExportError` – Gestionan el estado por día en
  la tabla `ExportedDay` (exportado localmente, subido, error, etc.).

Todos los accesos a DB se realizan sobre `Dispatchers.IO` dentro de
`StorageService`.  
Los llamadores deben evitar bloquear el hilo de UI.

---

## dataselector / Pickup

- El módulo `:dataselector` depende únicamente de la interfaz
  `LocationSampleSource` y no conoce Room ni `StorageService`.
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)`
    usa el intervalo semiabierto `[from, to)`.

Resumen de `SelectorRepository`:

- Modo **extracción directa** (`intervalSec == null`):
  - Se obtienen las muestras en orden ascendente, se aplica un filtro
    opcional por precisión, se aplica un límite y se ordena según
    `SortOrder` (más reciente primero o más antiguo primero).

- Modo **rejilla temporal** (`intervalSec != null`):
  - `T = intervalSec * 1000L`.
  - Para cada posición de la rejilla (creada desde el inicio o final
    según `SortOrder`) se selecciona una muestra representativa dentro
    de una ventana `±T/2` (si hay varias, se prefiere la más
    antigua).
  - Si no hay muestra para una posición de la rejilla, se devuelve
    `SelectedSlot(sample = null)` para representar huecos.

---

## GeoLocationService / GPS / Dead Reckoning

### Reparto de responsabilidades

- `GeoLocationService` (`:core`):
  - Usa `GpsLocationEngine` (implementado por
    `FusedLocationGpsEngine`) para recibir `GpsObservation` y
    convertirlos en `LocationSample` con `provider = "gps"`.
  - Mantiene una posición “hold de GPS” diferenciada de la posición
    recibida en bruto; esta posición hold se usa como ancla para
    Dead Reckoning y suaviza correcciones pequeñas sin perder
    respuesta en movimiento rápido.
  - Gestiona el ciclo de vida de `DeadReckoning`:
    `DeadReckoningFactory.create(applicationContext, config)` en
    `onCreate`, `start()` / `stop()` y llamadas a
    `submitGpsFix` / `predict`.
  - Ejecuta un ticker de DR:
    - Intervalo en segundos controlado por `drIntervalSec`.
    - Si `drIntervalSec == 0`, se desactiva DR (solo GPS).
    - Si `drIntervalSec > 0`, se arrancan iteraciones que llaman a
      `dr.predict(...)` y convierten el último `PredictedPoint` en
      `LocationSample` con `provider = "dead_reckoning"`, aplicando
      un guardado contra duplicados.
  - Consulta `dr.isLikelyStatic()` periódicamente y publica el valor
    en `DrDebugState` para que la pantalla de mapa pueda mostrar
    `Static: YES/NO` en la superposición de depuración.

- `DeadReckoning` (`:deadreckoning`):
  - Gestiona internamente:
    - Suscripción a sensores (acelerómetro/giroscopio).
    - Estado de posición y velocidad a lo largo de una línea 1D
      definida por la dirección GPS.
    - Detección de estático basada en velocidad efectiva y una
      ventana móvil de aceleración horizontal.
    - Límite de velocidad por paso (`maxStepSpeedMps`) para descartar
      saltos físicamente imposibles.
  - Expone:
    - `submitGpsFix(GpsFix)` – cada fix reancla la posición interna
      al hold de GPS más reciente.
    - `predict(fromMillis, toMillis)` – devuelve predicciones en el
      intervalo pedido; puede devolver una lista vacía antes del
      primer fix de GPS.
    - `isImuCapable()` – indica si hay sensores necesarios.
    - `isLikelyStatic()` – refleja el estado actual estático/móvil.

### Intervalos de muestreo y DR desactivado

- `SettingsRepository` (`storageservice.prefs`) define:
  - `intervalSecFlow(context)` – intervalo de GPS (segundos).
  - `drIntervalSecFlow(context)` – intervalo de DR (segundos):
    - `0` significa “Dead Reckoning desactivado (solo GPS)”.
    - `> 0` se limita a un mínimo de 1 segundo.
  - `currentIntervalMs` y `currentDrIntervalSec` son getters
    síncronos para compatibilidad; los Flows son preferibles para
    código nuevo.

- `GeoLocationService` se suscribe a ambos Flows:
  - Cambios en GPS → reinicia `GpsLocationEngine` con el nuevo
    intervalo si el servicio está en ejecución.
  - Cambios en DR → actualiza `drIntervalSec` mediante
    `applyDrInterval`:
    - `sec <= 0` → para el ticker de DR y evita crear muestras
      `"dead_reckoning"`.
    - `sec > 0` → arranca/bloquea un loop que llama a
      `dr.predict`.

- `IntervalSettingsViewModel` (UI):
  - Muestra campos para `GPS interval (sec)` y `DR interval (sec)` y
    un botón “Save & Apply”.
  - Valida:
    - GPS: mínimo 1 segundo.
    - DR:
      - `0` → desactiva DR (solo GPS) y muestra un mensaje
        explicativo.
      - Para valores > 0, exige `1 <= DR <= floor(GPS / 2)`.

---

## Exportación / subida / ajustes

### MidnightExportWorker / MidnightExportScheduler

- Tareas principales de `MidnightExportWorker`:
  - Leer la zona horaria desde `UploadPrefs.zoneId` (ID IANA, por
    defecto `Asia/Tokyo`) y procesar días en el rango
    `[00:00, 24:00)` de esa zona.
  - Usar `StorageService.ensureExportedDay`,
    `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,
    `exportedDayCount` y `lastSampleTimeMillis` para determinar:
    - Qué días procesar.
    - Qué resumen de estado (`backupStatus`) escribir para que la
      pantalla de ajustes de Drive pueda mostrar progreso.
  - Para cada día:
    - Cargar registros del rango correspondiente mediante
      `getLocationsBetween`.
    - Exportar a GeoJSON+ZIP en la carpeta de Descargas usando
      `GeoJsonExporter`.
    - Marcar la exportación local con `markExportedLocal`.
    - Resolver la carpeta de Drive efectiva (ID configurado en la UI
      via `DrivePrefsRepository`, con `AppPrefs.folderId` como
      reserva).
    - Crear un uploader usando `UploaderFactory` con
      `UploadEngine.KOTLIN`.
    - Subir el ZIP y marcar el día con `markUploaded` o
      `markExportError` según el resultado.
    - Borrar siempre el ZIP para evitar llenar el almacenamiento.
    - Borrar filas de `LocationSample` del día en cuestión solo si la
      subida se ha completado con éxito y existían registros.

- Acciones manuales:
  - “Backup days before today” llama a
    `MidnightExportWorker.runNow(context)` y fuerza un escaneo desde
    el primer día con muestras hasta ayer, usando
    `lastSampleTimeMillis` para determinar el rango.

### RealtimeUploadManager / ajustes de subida

- `RealtimeUploadManager` (`:datamanager`) observa nuevas filas de
  `LocationSample` y sube a Drive en función de los ajustes de
  Upload:
  - Se suscribe a los Flows de `UploadPrefsRepository`
    (`schedule`, `intervalSec`, `zoneId`) y de `SettingsRepository`
    (intervalos de GPS/DR).
  - Se suscribe a `StorageService.latestFlow(ctx, limit = 1)` para
    detectar nuevas muestras.
  - Solo actúa cuando:
    - `UploadSchedule.REALTIME` está seleccionado, y
    - Drive está configurado (cuenta + carpeta + engine válidos).
  - Cálculo del intervalo efectivo:
    - `intervalSec <= 0` → “subir en cada muestra nueva”.
    - Para valores > 0, actúa como cooldown basado en tiempo.
    - Si `intervalSec` coincide con el intervalo de muestreo activo
      (DR o GPS), también se interpreta como “cada muestra”.
  - Proceso de subida:
    - Cargar todas las filas de `LocationSample` con
      `getAllLocations`.
    - Generar GeoJSON y escribirlo en un archivo temporal en
      `cacheDir` llamado `YYYYMMDD_HHmmss.json` según la hora de la
      última muestra y la zona `zoneId`.
    - Resolver la carpeta de Drive combinando `DrivePrefsRepository`
      (ID de carpeta configurado en la UI) y `AppPrefs.folderId`.
    - Crear un uploader mediante `UploaderFactory.create(context,
      appPrefs.engine)`, que internamente usa
      `DriveTokenProviderRegistry` para obtener un
      `GoogleDriveTokenProvider`.
    - Subir el JSON y borrar el archivo temporal independientemente
      del resultado.
    - Si la subida tiene éxito, borrar las filas subidas mediante
      `StorageService.deleteLocations`.

- UI (`UploadSettingsScreen` / `UploadSettingsViewModel`):
  - Permite:
    - Activar/desactivar la subida automática (solo posible si Drive
      está configurado).
    - Elegir `UploadSchedule` (NONE / NIGHTLY / REALTIME).
    - Configurar `intervalSec` (0 o 1–86400).
    - Configurar `zoneId` (ID IANA) usada tanto por la exportación
      nocturna como por la vista previa de hoy.

---

## UI / Compose (resumen)

- `MainActivity`:
  - Contiene un `NavHost` a nivel de Activity con rutas `"home"`,
    `"drive_settings"`, `"upload_settings"`.
  - Solicita permisos en tiempo de ejecución mediante
    `ActivityResultContracts.RequestMultiplePermissions` y, tras
    concederse, inicia `GeoLocationService`.

- `AppRoot`:
  - Define un `NavHost` interno con rutas `"home"`, `"pickup"`,
    `"map"`.
  - AppBar:
    - Título según ruta (“GeoLocation”, “Pickup”, “Map”).
    - Botón de retroceso en Pickup y Map.
    - En Home, muestra botones `Map`, `Pickup`, `Drive`, `Upload` y
      el componente `ServiceToggleAction` para controlar el
      arranque/parada del servicio.

- `MapScreen` / `GoogleMapsExample`:
  - Usa `GoogleMapsView` de MapConductor con backend de Google Maps.
  - Fila superior:
    - Checkboxes para `GPS` y `DeadReckoning`.
    - Campo `Count` (1–5000).
    - Botones `Apply` / `Cancel`.
  - `Apply`:
    - Bloquea los controles y dibuja polilíneas por proveedor usando
      hasta `Count` muestras más recientes:
      - GPS: polilínea azul, trazo grueso, dibujada antes (detrás).
      - DR : polilínea roja, trazo fino, dibujada después (delante).
    - Las muestras se conectan **por orden temporal** (`timeMillis`),
      no por distancia.
  - `Cancel`:
    - Borra las polilíneas y desbloquea los controles, manteniendo la
      posición/zoom actual de la cámara.
  - Superposición de depuración:
    - Muestra `GPS`, `DR`, `ALL` como `mostrados / total BD`.
    - Muestra el flag de estático de `DrDebugState`.
    - Muestra la distancia DR–GPS, precisión GPS y un “peso” interno
      que refleja la influencia de GPS en el motor.
  - Círculo de precisión para el último GPS:
    - Centro: última muestra GPS.
    - Radio: `accuracy` en metros.
    - Borde azul fino y relleno semitransparente.

---

## Estilo de código y comentarios (resumen)

- Todo el **código de producción** (Kotlin / Java / XML / scripts
  Gradle, etc.) debe escribirse usando solo caracteres ASCII.
  - Esto incluye comentarios y literales de cadena: evita caracteres
    multibyte en código.
- El contenido en japonés/español se limita a archivos de
  documentación (`*.md`) como
  `README_JA.md`, `README_ES.md`, `AGENTS_JA.md`, `AGENTS_ES.md`.
- APIs públicas y clases clave deben documentarse con KDoc
  (`/** ... */`), describiendo rol, política de diseño, uso y
  contrato.  
  Los detalles internos se comentan con `//` simples y encabezados
  de sección uniformes (`// ---- Section ----`).

---

## Superficie de API pública (librería)

En general, se espera que solo los siguientes tipos formen parte de la
superficie pública estable:

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`,
    `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`,
    `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`:
  - Autenticación/tokens:
    `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - Ajustes:
    `DrivePrefsRepository`, `UploadPrefsRepository`, `UploadSchedule`
  - API de Drive:
    `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - Exportación y subida:
    `UploadEngine` (en `:core.config`), `GeoJsonExporter`,
    `Uploader`, `UploaderFactory`, `RealtimeUploadManager`
  - Trabajos en segundo plano:
    `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`:
  - `GpsLocationEngine`, `GpsObservation`,
    `FusedLocationGpsEngine`

Los tipos no listados aquí deben considerarse detalles de
implementación (idealmente `internal`) y pueden cambiar sin aviso.

