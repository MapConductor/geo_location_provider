# :dataselector (Pickup / Selección de datos)

Módulo Gradle: `:dataselector`

Este módulo implementa lógica de selección ("pickup"): extrae `LocationSample` en un rango de tiempo y opcionalmente los ajusta a una grilla temporal, devolviendo `SelectedSlot` (incluyendo gaps).

## Contrato de frontera (lo que provees -> lo que obtienes)

Provees:

- `LocationSampleSource` que retorna muestras en orden ascendente para `[fromInclusive, toExclusive)`.
- `SelectorCondition` con el rango/orden/límites.

Obtienes:

- Lista de `SelectedSlot` con una muestra representativa o `sample = null` para gaps.

Este módulo no conoce Room/DAOs. La app host decide cómo cargar datos (normalmente vía `StorageService`).

## Conceptos

- `LocationSampleSource`
- `SelectorCondition`
- `SelectedSlot`
- `SelectorRepository`

## Uso típico

Implementa `LocationSampleSource` delegando a `StorageService`:

```kotlin
class StorageSource(private val ctx: Context) : LocationSampleSource {
  override suspend fun findBetween(fromInclusive: Long, toExclusive: Long): List<LocationSample> {
    return StorageService.getLocationsBetween(ctx, fromInclusive, toExclusive)
  }
}
```

Ejecuta la selección:

```kotlin
val repo = SelectorRepository(source = StorageSource(ctx))
val slots = repo.select(
  SelectorCondition(
    fromMillis = from,
    toMillis = to,
    intervalSec = 5,
    order = SortOrder.NewestFirst,
    limit = 1000,
    minAccuracy = null
  )
)
```

## Recetas

- Directo (`intervalSec == null`): sin grilla, sin gaps.
- Grilla (`intervalSec != null`): timeline regular, gaps con `sample = null`, ventana `+-T/2`.
- `minAccuracy`: filtra candidatos y puede aumentar gaps.

## Uso detallado en fronteras (cómo leer el resultado)

### Interpretar `SelectedSlot`

Lo que obtienes:

- `SelectedSlot.idealMs`: timestamp objetivo de la grilla (o timestamp de la muestra en modo directo)
- `SelectedSlot.sample`:
  - no null: muestra representativa cerca de `idealMs`
  - null: gap explícito (no hubo candidato en la ventana)
- `SelectedSlot.deltaMs`: `sample.timeMillis - idealMs` (null cuando `sample == null`)

Ejemplo de consumo:

```kotlin
slots.forEach { slot ->
  val sample = slot.sample
  if (sample == null) {
    // gap en slot.idealMs
  } else {
    // punto en sample.lat/sample.lon con timestamp sample.timeMillis
  }
}
```

### `SortOrder` controla la dirección de la grilla

Lo que escribes:

- `OldestFirst`: objetivos avanzan hacia adelante en el tiempo.
- `NewestFirst`: objetivos avanzan hacia atrás en el tiempo.

Lo que obtienes:

- El orden de la lista coincide con el orden natural de render (timeline forward o reverse).
- Con `limit`, puedes construir vistas tipo "últimos 15 min a 5s".

### Prefiere rangos acotados

Lo que escribes:

- Para UI, define `fromMillis` y `toMillis` para evitar scans sin límite.

Lo que obtienes:

- Performance y memoria más predecibles.
