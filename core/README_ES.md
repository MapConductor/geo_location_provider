# :core (Servicio de ubicación en primer plano)

Módulo Gradle: `:core`

Este módulo provee el servicio en primer plano `GeoLocationService`. Es el orquestador en tiempo de ejecución: recibe observaciones GPS, agrega señales (rumbo, calidad GNSS, corrección opcional), integra Dead Reckoning (opcional) y persiste `LocationSample` vía `:storageservice`.

## Responsabilidades

- Ciclo de vida del tracking en primer plano (start/stop, notificación).
- Manejo de observaciones vía `:gps` (`GpsLocationEngine`).
- Corrección GPS opcional vía `GpsCorrectionEngineRegistry`.
- Integración DR (`DeadReckoningFactory`, predicción/relleno).
- Persistencia vía `StorageService` (sin DAOs fuera de `:storageservice`).

## Punto de entrada

- `GeoLocationService`: `core/src/main/java/com/mapconductor/plugin/provider/geolocation/service/GeoLocationService.kt`

## Contrato de frontera (entradas -> salidas)

Entradas (app host):

- Declarar el servicio en el manifest y arrancar/parar desde UI.
- Ajustes vía `:storageservice` (`SettingsRepository`):
  - intervalo GPS, intervalo DR, modo DR, y throttle GPS->DR (opcional)
- (Opcional) instalar un motor de corrección (por ejemplo `ImsEkf.install(...)`).

Salidas:

- Inserta `LocationSample` vía `StorageService.insertLocation(...)`:
  - `provider="gps"`
  - `provider="gps_corrected"` (si hay corrección y difiere)
  - `provider="dead_reckoning"` (si DR está habilitado)

Efectos:

- Mantiene notificación en primer plano.
- Inicia/detiene sensores según necesidad.
- Evita inserts duplicados con un guard simple.

## Control por Intents

Ver constantes en `GeoLocationService.kt`:

- `ACTION_START` / `ACTION_STOP`
- `ACTION_UPDATE_INTERVAL` + `EXTRA_UPDATE_MS`
- `ACTION_UPDATE_DR_INTERVAL` + `EXTRA_DR_INTERVAL_SEC`
- `ACTION_UPDATE_DR_GPS_INTERVAL` + `EXTRA_DR_GPS_INTERVAL_SEC`

## Integración en una app

```kotlin
dependencies {
  implementation(project(":core"))
}
```

Arranque:

```kotlin
val intent = Intent(context, GeoLocationService::class.java).apply {
  action = GeoLocationService.ACTION_START
}
ContextCompat.startForegroundService(context, intent)
```

Parar:

```kotlin
val intent = Intent(context, GeoLocationService::class.java).apply {
  action = GeoLocationService.ACTION_STOP
}
context.startService(intent)
```

## Recetas (qué hacer -> qué ocurre)

- Solo tracking: se insertan muestras `provider="gps"`.
- DR habilitado: se insertan muestras `provider="dead_reckoning"` según el modo.
- Corrección EKF habilitada: se insertan muestras `provider="gps_corrected"` cuando aplica.
- Cambios de settings: el servicio observa flows y actualiza el comportamiento en vivo.

## Uso detallado en fronteras de módulos (código -> efectos)

Esta sección muestra ejemplos concretos de "qué escribes en la app host" y "qué cambia en el sistema".

### Habilitar corrección EKF end-to-end

Lo que escribes:

- Instalar el corrector EKF al inicio del proceso.
- Habilitarlo persistiendo `ImsEkfConfig` vía `ImsEkfConfigRepository` con `enabled=true`.

```kotlin
// Application.onCreate()
ImsEkf.install(appContext)
ImsEkfConfigRepository.set(
  appContext,
  ImsEkfConfig.defaults(ImsUseCase.WALK).copy(enabled = true)
)
```

Lo que obtienes:

- `GeoLocationService` inicia el ciclo de vida del motor de corrección mientras corre el servicio.
- En cada observación GPS, el corrector puede producir una observación fusionada.
- Si la fusionada difiere de la raw, se inserta una `LocationSample(provider="gps_corrected")` adicional.
- La app puede visualizar este stream por separado (aparece como `GPS(EKF)` en la UI).

Error común:

- Instalar EKF sin `enabled=true` no produce muestras `gps_corrected`.

### Actualizar sampling sin reiniciar el servicio

Lo que escribes (desde UI/ViewModel; estas APIs son `suspend`):

```kotlin
viewModelScope.launch {
  SettingsRepository.setIntervalSec(context, sec = 1)
  SettingsRepository.setDrIntervalSec(context, sec = 0) // DR deshabilitado
  SettingsRepository.setDrMode(context, mode = DrMode.Prediction)
}
```

Lo que obtienes:

- El servicio en ejecución observa flows de `SettingsRepository` y actualiza:
  - el intervalo de GPS
  - el ticker de DR (start/stop)
  - el modo DR (Prediction vs Completion)
- No hace falta reiniciar el servicio.

### Elegir modo DR y entender el resultado

Lo que escribes:

- `drIntervalSec = 0` deshabilita DR completamente.
- `drIntervalSec > 0` habilita DR; `drMode` define la forma de salida.

Lo que obtienes:

- `DrMode.Prediction`:
  - inserta puntos extra en tiempo real aprox. cada `drIntervalSec` como `provider="dead_reckoning"`
- `DrMode.Completion`:
  - no emite puntos por ticker
  - inserta puntos backfill entre fixes GPS como `provider="dead_reckoning"`

### Consumir providers explícitamente (GPS vs corregido vs DR)

Lo que escribes:

- Filtra por `LocationSample.provider` cuando quieres un stream específico para UI/export/diagnóstico.

```kotlin
StorageService.latestFlow(context, limit = 500).collect { rows ->
  val gps = rows.filter { it.provider == "gps" }
  val ekf = rows.filter { it.provider == "gps_corrected" }
  val dr = rows.filter { it.provider == "dead_reckoning" }
}
```

Lo que obtienes:

- Separación estable entre GPS raw, GPS corregido y DR.
- Comparación A/B más sencilla en la pantalla de Map.

### Cambiar el motor GPS (avanzado)

Lo que escribes:

- Implementa otro `GpsLocationEngine` en `:gps` (por ejemplo basado en Google Play Services).
- Cambia `GeoLocationService` para instanciar tu engine en lugar de `LocationManagerGpsEngine`.

Lo que obtienes:

- Persistencia y DR siguen igual; solo cambia la fuente de `GpsObservation`.

## Módulos relacionados

- `../gps/README.md`
- `../storageservice/README.md`
- `../deadreckoning/README.md`
