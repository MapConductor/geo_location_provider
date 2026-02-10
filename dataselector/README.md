# :dataselector (Pickup / Data Selection)

Gradle module: `:dataselector`

This module implements "pickup" selection logic: extracting `LocationSample` rows in a time range and optionally snapping them to a fixed time grid, producing `SelectedSlot` results (including gaps).

## Module boundary contract (what you provide -> what you get)

You provide:

- a `LocationSampleSource` implementation that returns samples in ascending order for `[fromInclusive, toExclusive)`
- a `SelectorCondition` describing your desired extraction

You get:

- a list of `SelectedSlot` values, each representing either:
  - a chosen representative `LocationSample`, or
  - a gap (`sample = null`) when no candidate exists in the window

This module does not know about Room/DAOs. The host app decides how to load data (usually via `StorageService`).

## Key concepts

- `LocationSampleSource`:
  - a minimal abstraction to fetch samples in `[fromInclusive, toExclusive)` in ascending order
  - keeps this module independent from Room/DAO details
- `SelectorCondition`:
  - describes time range, optional grid interval, ordering, limits, and filters
- `SelectedSlot`:
  - one grid target with either a chosen `LocationSample` or `null` for a gap
- `SelectorRepository`:
  - the main implementation that applies the above rules

## Main API surface

- `LocationSampleSource`: `dataselector/src/main/java/com/mapconductor/plugin/provider/geolocation/repository/LocationSampleSource.kt`
- `SelectorCondition`, `SortOrder`, `SelectedSlot`: `dataselector/src/main/java/com/mapconductor/plugin/provider/geolocation/condition/SelectorCondition.kt`
- `SelectorRepository`: `dataselector/src/main/java/com/mapconductor/plugin/provider/geolocation/repository/SelectorRepository.kt`

## Typical usage

Provide a `LocationSampleSource` (for example by delegating to `StorageService` in your app module):

```kotlin
class StorageSource(private val ctx: Context) : LocationSampleSource {
  override suspend fun findBetween(fromInclusive: Long, toExclusive: Long): List<LocationSample> {
    return StorageService.getLocationsBetween(ctx, fromInclusive, toExclusive)
  }
}
```

Then run selection:

```kotlin
val repo = SelectorRepository(source = StorageSource(ctx))
val slots: List<SelectedSlot> = repo.select(
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

## Recipes (condition -> result shape)

### Direct extraction (no grid)

Condition:

- `intervalSec == null`

Result:

- You get one `SelectedSlot` per sample (no gaps).
- Ordering is applied at the end based on `SortOrder`.

When to use:

- UI previews, simple "latest N" lists, or exporting raw data.

### Grid snapping (regular timeline + gaps)

Condition:

- `intervalSec != null` (seconds)

Result:

- Targets are generated based on `from/to` and `SortOrder`.
- Each target uses a window of `+-T/2` and picks one representative sample.
- If no candidate exists, you get `SelectedSlot(sample = null)` to represent a gap.

When to use:

- "Pickup" style selection where a regular cadence is required.

Effect of `minAccuracy`

- When `minAccuracy` is set, candidates with `accuracy > minAccuracy` are filtered out.
- This can increase gaps when GPS quality is poor.

## Effect of `intervalSec` (summary)

- `intervalSec == null`:
  - direct extraction (no grid)
  - you typically use this when you want "the latest N samples" with minimal processing
- `intervalSec != null`:
  - grid snapping with window `+-T/2` (T = `intervalSec * 1000`)
  - you typically use this when you want a regular timeline and explicit gaps

## Detailed cross-module usage (how to read the result)

### Interpret `SelectedSlot` correctly

What you get:

- `SelectedSlot.idealMs`: the target timestamp on the grid (or the sample timestamp in direct mode)
- `SelectedSlot.sample`:
  - non-null: the chosen representative sample around `idealMs`
  - null: an explicit gap (no sample in the window)
- `SelectedSlot.deltaMs`: `sample.timeMillis - idealMs` (null when `sample == null`)

What you write (rendering example):

```kotlin
slots.forEach { slot ->
  val sample = slot.sample
  if (sample == null) {
    // gap at slot.idealMs
  } else {
    // show point sample.lat/sample.lon at sample.timeMillis
    // slot.deltaMs tells how far it is from the ideal grid time
  }
}
```

### Use `SortOrder` to control the "direction" of the grid

What you write:

- `SortOrder.OldestFirst`: grid targets grow forward in time.
- `SortOrder.NewestFirst`: grid targets grow backward in time.

What you get:

- The result list order matches the order you will likely render (timeline forward or reverse).
- Combined with `limit`, this lets you build views like "the latest 15 minutes at 5 sec cadence".

### Prefer bounded ranges to avoid loading too much

What you write:

- Always set `fromMillis` and `toMillis` for UI usage unless you explicitly want an unbounded scan.

What you get:

- Predictable performance and memory usage because `LocationSampleSource.findBetween` can stay bounded.

## Notes

- Time ranges are half-open: `[from, to)`.
- When `intervalSec == null`, selection is "direct" (no grid).
- When `intervalSec != null`, selection snaps to a grid and represents gaps with `sample = null`.
