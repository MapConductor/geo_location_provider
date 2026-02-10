# :deadreckoning (Dead Reckoning con IMU)

Módulo Gradle: `:deadreckoning`

Este módulo provee un motor de dead reckoning basado en IMU que puede predecir posiciones intermedias entre fixes GPS. La API pública es pequeña y se accede vía `DeadReckoningFactory`.

## Contrato de frontera (entradas -> salidas)

Entradas:

- Sensores IMU disponibles.
- Fix GPS vía `submitGpsFix(...)`.
- Rango de tiempo vía `predict(fromMillis, toMillis)`.

Salidas:

- Lista de `PredictedPoint`.
- Heurística `isLikelyStatic()`.

## API pública

- `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`

## Uso típico

```kotlin
val dr = DeadReckoningFactory.create(appContext, DeadReckoningConfig())
dr.start()
dr.submitGpsFix(GpsFix(timestampMillis = t, lat = lat, lon = lon, accuracyM = acc, speedMps = speed))
val points = dr.predict(fromMillis = t0, toMillis = t1)
```

## Uso detallado en fronteras (código -> efectos)

### Patrón: predicción en tiempo real (modo Prediction)

Lo que escribes:

- Envía fixes GPS con `submitGpsFix(...)`.
- Llama `predict(lastFixMillis, nowMillis)` periódicamente (ticker).

Lo que obtienes:

- Una lista de `PredictedPoint` intermedios para una trayectoria más suave.
- En integración con `:core`, esto suele persistirse como `LocationSample(provider="dead_reckoning")` en un ticker.

### Patrón: backfill entre fixes GPS (modo Completion)

Lo que escribes:

- En cada nuevo fix GPS, llama `predict(prevFixMillis, currentFixMillis)` una sola vez.

Lo que obtienes:

- Un puente denso de puntos entre dos anclas GPS sin emitir puntos extra por ticker.

### Patrón: reducir jitter cuando está estático

Lo que escribes:

- Consulta `dr.isLikelyStatic()` y usa ese hint para clamping (por ejemplo congelar marker o reducir updates).

Lo que obtienes:

- Menos jitter cuando el dispositivo probablemente no se mueve.

### Patron: ajustar `DeadReckoningConfig`

Lo que escribes:

- Empieza con defaults y ajusta solo lo necesario:
  - `velocityGain`
  - `maxStepSpeedMps`
  - `staticAccelVarThreshold` / `staticGyroVarThreshold`

Lo que obtienes:

- Salida más estable según tu perfil de movimiento y sensores.

## Módulos relacionados

- `../core/README.md`
