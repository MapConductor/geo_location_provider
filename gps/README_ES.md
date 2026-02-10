# :gps (Abstracción de GPS)

Módulo Gradle: `:gps`

Este módulo provee una abstracción pequeña sobre actualizaciones de ubicación para que el resto del código use un modelo estable (`GpsObservation`) y un motor intercambiable (`GpsLocationEngine`).

## Contrato de frontera

Entradas:

- La app host provee `Context` y `Looper` (hilo para callbacks).
- La app host concede permisos de ubicación.

Salidas:

- Un stream de `GpsObservation` vía `GpsLocationEngine.Listener`.
  - Incluye lat/lon/accuracy y metadatos GNSS opcionales (satélites usados/total, CN0 mean).

## API pública

- `GpsLocationEngine`: start/stop, actualizar intervalo, asignar listener
- `GpsObservation`: una observación de ubicación con metadatos GNSS opcionales

Archivos:

- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/GpsLocationEngine.kt`
- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/GpsObservation.kt`

## Implementación incluida

- `LocationManagerGpsEngine`
  - Usa `LocationManager` de Android y `GnssStatus.Callback`.
  - Pensado para entornos sin Google Play Services.

Archivo:

- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/LocationManagerGpsEngine.kt`

## Uso típico

```kotlin
val engine = LocationManagerGpsEngine(appContext, Looper.getMainLooper())
engine.setListener(object : GpsLocationEngine.Listener {
  override fun onGpsObservation(observation: GpsObservation) {
    // manejar la observación
  }
})
engine.updateInterval(5_000L)
engine.start()
```

Parar:

```kotlin
engine.stop()
engine.setListener(null)
```

## Permisos

- `android.permission.ACCESS_FINE_LOCATION` (recomendado)
- o `android.permission.ACCESS_COARSE_LOCATION`

## Implementar tu propio `GpsLocationEngine` (avanzado)

Si quieres usar otro proveedor (por ejemplo Google Play Services), implementa `GpsLocationEngine` dentro de este módulo.

Lo que escribes:

- Una implementación que traduzca callbacks de plataforma a valores `GpsObservation`.

Lo que obtienes:

- `:core` consume observaciones sin depender de tipos Android especificos.
- Persistencia y logica aguas abajo no cambian; solo cambia la fuente de observaciones.

Esqueleto:

```kotlin
class MyGpsEngine : GpsLocationEngine {
  private var listener: GpsLocationEngine.Listener? = null

  override fun setListener(listener: GpsLocationEngine.Listener?) {
    this.listener = listener
  }

  override fun updateInterval(intervalMs: Long) {
    // actualizar intervalo en la plataforma
  }

  override fun start() {
    // empezar updates y llamar emit(...)
  }

  override fun stop() {
    // parar updates
  }

  private fun emit(observation: GpsObservation) {
    listener?.onGpsObservation(observation)
  }
}
```

Tips:

- Usa tiempo monótono (`elapsedRealtimeNanos`) cuando sea posible.
- Metadatos GNSS son opcionales; usa `null` si no están disponibles.

## Notas

- Mantener la integración GPS testeable y reemplazable.
- No expongas tipos Android fuera de este módulo; usa `GpsObservation`.
