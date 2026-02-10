# :storageservice (Room + Fachada StorageService)

Módulo Gradle: `:storageservice`

Este módulo es responsable de la persistencia:

- Base de datos Room (`AppDatabase`) y entidades/DAOs (internos a este módulo).
- `StorageService`, la única fachada soportada para acceder a la DB desde otros módulos.
- `SettingsRepository`, DataStore para ajustes de muestreo y DR.

## Regla de capas

Otros módulos no deben importar tipos DAO de Room directamente. Usa `StorageService`.

## Tipos expuestos (frontera)

- `LocationSample`
- `ExportedDay`
- `StorageService`
- `SettingsRepository`

## Contrato (qué llamas -> qué obtienes)

- Semántica estable de orden/rango.
- Trabajo de DB en `Dispatchers.IO` dentro del módulo.
- El llamador no necesita conocer DAOs ni queries.

## APIs principales

Archivo: `storageservice/src/main/java/com/mapconductor/plugin/provider/storageservice/StorageService.kt`

- `latestFlow(ctx, limit)`: newest-first
- `getLocationsBetween(ctx, from, to, softLimit)`: rango half-open `[from, to)` ascendente
- `insertLocation(ctx, sample)`, `deleteLocations(ctx, items)`
- helpers `ExportedDay` para estado por día

`SettingsRepository` (DataStore):

- `intervalSecFlow`, `drIntervalSecFlow`, `drGpsIntervalSecFlow`, `drModeFlow`

## Recetas

- UI "últimos N": `latestFlow(limit=N)` -> lista newest-first que se actualiza.
- Export diario: `getLocationsBetween([from,to))` -> lista ascendente.
- Estado de backup: `ensureExportedDay`, `markExportedLocal`, `markUploaded`, `markExportError`.

- Evitar `getAllLocations` para datasets grandes:
  - carga toda la tabla en memoria; usarlo solo para previews pequeñas o debugging.

## Uso detallado en fronteras (código -> efectos)

### Cortar por día correctamente (según zona horaria)

Lo que escribes:

- Convierte un día local de una zona horaria a un rango half-open `[from, to)` en milisegundos.

```kotlin
val zoneId = ZoneId.of("Asia/Tokyo")
val day = LocalDate.now(zoneId)
val fromMillis = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
val toMillis = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
val rows = StorageService.getLocationsBetween(ctx, fromMillis, toMillis)
```

Lo que obtienes:

- Slicing por día correcto incluso con cambios de offset/DST.
- Sin solapes entre días adyacentes gracias al contrato `[from, to)`.

### Alimentar a :dataselector sin filtrar detalles de Room

Lo que escribes:

- Implementa `LocationSampleSource` en el módulo host delegando en `StorageService`.

```kotlin
class StorageSource(private val ctx: Context) : LocationSampleSource {
  override suspend fun findBetween(fromInclusive: Long, toExclusive: Long): List<LocationSample> {
    return StorageService.getLocationsBetween(ctx, fromInclusive, toExclusive)
  }
}
```

Lo que obtienes:

- `:dataselector` puede ejecutar lógica de pickup/grilla sin conocer DAOs ni Room.
- Se preservan las garantías de rango/orden end-to-end.

### Drenar la DB solo después de un upload exitoso

Lo que escribes:

- Borra filas solo cuando el upload a Drive fue OK.

```kotlin
val uploaded: Boolean = true // resultado del uploader
if (uploaded) {
  StorageService.deleteLocations(ctx, rows)
}
```

Lo que obtienes:

- Patrón "upload as drain": si falla, los datos quedan para reintento.

### Seguir progreso nocturno con ExportedDay

Lo que escribes:

- Usa `epochDay` como clave por día y actualiza estado durante el worker.

```kotlin
val epochDay = day.toEpochDay()
StorageService.ensureExportedDay(ctx, epochDay)
StorageService.markExportedLocal(ctx, epochDay)
// ... upload ...
StorageService.markUploaded(ctx, epochDay, fileId = "drive_file_id")
```

Lo que obtienes:

- Workers pueden reanudar desde estado persistido por día.
- UI puede mostrar progreso sin escanear todas las muestras.

## Módulos relacionados

- `../core/README.md`
- `../datamanager/README.md`
- `../dataselector/README.md`
