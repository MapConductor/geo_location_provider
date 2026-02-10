# :storageservice (Room + StorageService Facade)

Gradle module: `:storageservice`

This module owns persistence:

- Room database (`AppDatabase`) and entities/DAOs (internal to this module).
- `StorageService`, a thin facade that is the only supported entry point for database access from other modules.
- `SettingsRepository`, a DataStore-based store for sampling and DR settings.

## Layering rule

Other modules must not import Room DAO types directly. Use `StorageService` instead.

## Cross-module types (API surface)

Other modules are expected to use these public types from this module:

- `LocationSample`: the persisted time series record used across the system
- `ExportedDay`: per-day export/upload state used by the backup pipeline
- `StorageService`: the facade for all DB reads/writes
- `SettingsRepository`: DataStore settings for sampling and DR

## Module boundary contract (what you write -> what you get)

When another module calls `StorageService`:

- you get stable ordering and range semantics (documented below)
- DB work happens on `Dispatchers.IO` inside the module
- callers do not need to know about DAOs, tables, or queries

## Main APIs

### `StorageService`

File: `storageservice/src/main/java/com/mapconductor/plugin/provider/storageservice/StorageService.kt`

Common operations:

- `latestFlow(ctx, limit)`: newest-first stream of recent `LocationSample` rows.
- `getAllLocations(ctx)`: full snapshot, ascending by time (use only for small datasets).
- `getLocationsBetween(ctx, from, to, softLimit)`: range query, `[from, to)`, ascending.
- `insertLocation(ctx, sample)`: inserts one sample (logs `DB/TRACE` in debuggable builds).
- `deleteLocations(ctx, items)`: batch delete (no-op on empty list).
- `lastSampleTimeMillis(ctx)` / `firstSampleTimeMillis(ctx)`: diagnostics / scheduling.
- `ExportedDay` helpers: seed and update per-day export/upload status.

All DB work runs on `Dispatchers.IO` inside `StorageService`.

#### Ordering and range semantics (important)

- `latestFlow` / `latestOnce`:
  - ordered newest-first by `timeMillis` (with stable tie-breakers).
- `getLocationsBetween`:
  - half-open time range: `[from, to)`
  - ordered ascending by `timeMillis` (time series friendly)
- `getLocationsBetweenDesc` / `getLocationsBeforeDesc`:
  - newest-first ordering for UI clipping/paging style usage

### `SettingsRepository`

File: `storageservice/src/main/java/com/mapconductor/plugin/provider/storageservice/prefs/SettingsRepository.kt`

Settings flows used by the service/UI:

- `intervalSecFlow(context)`: GPS interval seconds (defaults + minimum applied)
- `drIntervalSecFlow(context)`: DR interval seconds (0 disables DR)
- `drGpsIntervalSecFlow(context)`: GPS->DR submit throttle (0 = every fix)
- `drModeFlow(context)`: Prediction vs Completion

What you write:

- Call `SettingsRepository.setIntervalSec(...)`, `setDrIntervalSec(...)`, etc.

Effect:

- `:core` observes these flows and updates sampling and DR behavior while the service is running.

## Recipes (what to call -> what you get)

### Recipe: Show "latest N" in UI

What you do:

- Collect `StorageService.latestFlow(ctx, limit = N)` in a ViewModel.

What you get:

- A newest-first list that updates as new rows are inserted.
- Stable ordering even when multiple rows share the same timestamp.

### Recipe: Export a day range

What you do:

- Convert a local date range into `[fromMillis, toMillis)` and call:
  - `StorageService.getLocationsBetween(ctx, fromMillis, toMillis)`

What you get:

- A time series list ordered ascending by `timeMillis`.
- A half-open range contract that is safe for daily slicing.

### Recipe: Avoid expensive full snapshots

What you do:

- Prefer range queries (`getLocationsBetween`) or "latest N" (`latestFlow`) for most UI and worker logic.

What happens if you use `getAllLocations`:

- It loads the entire table into memory and should be limited to small datasets (debugging, small previews, realtime snapshot upload).

### Recipe: Support nightly backup status

What you do:

- In an export worker, call:
  - `ensureExportedDay(epochDay)`
  - `markExportedLocal(epochDay)`
  - `markUploaded(epochDay, fileId)`
  - `markExportError(epochDay, msg)`

What you get:

- A persisted, queryable status log per day that workers can resume from and UIs can display.

## Typical usage

Read the latest N items:

```kotlin
StorageService.latestFlow(ctx, limit = 9).collect { rows ->
  // rows sorted newest-first
}
```

Insert one sample:

```kotlin
StorageService.insertLocation(ctx, sample)
```

Delete uploaded samples:

```kotlin
StorageService.deleteLocations(ctx, items)
```

## Detailed cross-module usage (what you write -> what you get)

### Build a day slice correctly (timezone-safe)

What you write:

- Convert a local date in a specific timezone into a half-open `[from, to)` range.

```kotlin
val zoneId = ZoneId.of("Asia/Tokyo")
val day = LocalDate.now(zoneId)
val fromMillis = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
val toMillis = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
val rows = StorageService.getLocationsBetween(ctx, fromMillis, toMillis)
```

What you get:

- Correct per-day slicing even across DST/offset changes.
- No overlaps and no gaps between adjacent days because the contract is `[from, to)`.

### Feed :dataselector without leaking Room details

What you write:

- Implement `LocationSampleSource` in your host module by delegating to `StorageService`.

```kotlin
class StorageSource(private val ctx: Context) : LocationSampleSource {
  override suspend fun findBetween(fromInclusive: Long, toExclusive: Long): List<LocationSample> {
    return StorageService.getLocationsBetween(ctx, fromInclusive, toExclusive)
  }
}
```

What you get:

- `:dataselector` can run pickup/grid logic without knowing about Room or DAOs.
- The ordering/range guarantees are preserved end-to-end.

### Drain the DB only after successful upload

What you write:

- After a Drive upload succeeds, delete the rows you uploaded.

```kotlin
val uploaded: Boolean = true // result from uploader
if (uploaded) {
  StorageService.deleteLocations(ctx, rows)
}
```

What you get:

- A predictable "upload as drain" workflow (used by realtime upload and per-day backlog upload).
- If upload fails, data remains in the DB for retry.

### Track nightly progress with ExportedDay

What you write:

- Convert a day into an `epochDay` key and update status as the worker runs.

```kotlin
val epochDay = day.toEpochDay()
StorageService.ensureExportedDay(ctx, epochDay)
StorageService.markExportedLocal(ctx, epochDay)
// ... upload ...
StorageService.markUploaded(ctx, epochDay, fileId = "drive_file_id")
```

What you get:

- Workers can resume from persisted per-day state.
- UI can show "what is exported/uploaded" without scanning raw samples.

## Related modules

- `../core/README.md`
- `../datamanager/README.md`
- `../dataselector/README.md`
