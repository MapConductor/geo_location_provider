# :dataselector (Pickup / データ選択)

Gradle モジュール: `:dataselector`

本モジュールは "pickup" (選択) ロジックを実装します。時間範囲で `LocationSample` を抽出し、必要に応じて時間グリッドへスナップし、欠損(ギャップ)も含めて `SelectedSlot` を返します。

## モジュール境界の契約(提供するもの -> 得られるもの)

提供するもの:

- `[fromInclusive, toExclusive)` を昇順で返す `LocationSampleSource` 実装
- 抽出条件 `SelectorCondition`

得られるもの:

- `SelectedSlot` のリスト
  - 代表サンプルを持つスロット、または
  - ギャップを示す `sample = null` のスロット

本モジュールは Room/DAO を知りません。データ取得方法はホスト側が決めます(通常は `StorageService` 経由)。

## 概念

- `LocationSampleSource`:
  - `[fromInclusive, toExclusive)` を昇順で取得する最小インターフェイス
  - Room/DAO の詳細に依存しないための抽象
- `SelectorCondition`:
  - 期間、グリッド間隔(任意)、並び順、上限、フィルタなど
- `SelectedSlot`:
  - 1 つのターゲットに対し、代表サンプルまたは `null` (ギャップ)を持つ
- `SelectorRepository`:
  - 上記のルールを適用する実装本体

## 主な API

- `LocationSampleSource`: `dataselector/src/main/java/com/mapconductor/plugin/provider/geolocation/repository/LocationSampleSource.kt`
- `SelectorCondition`, `SortOrder`, `SelectedSlot`: `dataselector/src/main/java/com/mapconductor/plugin/provider/geolocation/condition/SelectorCondition.kt`
- `SelectorRepository`: `dataselector/src/main/java/com/mapconductor/plugin/provider/geolocation/repository/SelectorRepository.kt`

## 典型的な使い方

`LocationSampleSource` を用意します(例: app 側で `StorageService` に委譲):

```kotlin
class StorageSource(private val ctx: Context) : LocationSampleSource {
  override suspend fun findBetween(fromInclusive: Long, toExclusive: Long): List<LocationSample> {
    return StorageService.getLocationsBetween(ctx, fromInclusive, toExclusive)
  }
}
```

選択を実行:

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

## レシピ(条件 -> 結果の形)

### 直抽出(グリッドなし)

条件:

- `intervalSec == null`

結果:

- サンプルごとに `SelectedSlot` が 1 つ(ギャップなし)
- `SortOrder` に応じて最終的な並び順が決まります

用途:

- UI プレビュー、単純な "最新 N 件"、raw データの出力

### グリッドスナップ(一定間隔 + ギャップ)

条件:

- `intervalSec != null`

結果:

- `from/to` と `SortOrder` からターゲットが生成されます
- 各ターゲットは `+-T/2` の窓で代表サンプルを 1 つ選びます
- 見つからなければ `SelectedSlot(sample = null)` になります

用途:

- 一定間隔での選択や、欠損を明示したいケース

`minAccuracy` の効果:

- `accuracy > minAccuracy` のサンプルが除外されます
- 品質が悪いとギャップが増える可能性があります

## モジュール境界の使い方(詳細: 結果の読み方)

### `SelectedSlot` を正しく解釈する

得られるもの:

- `SelectedSlot.idealMs`: グリッドの理想時刻(直抽出ではサンプル時刻)
- `SelectedSlot.sample`:
  - non-null: `idealMs` 付近で選ばれた代表サンプル
  - null: 欠損(窓内に候補が無い)を明示するギャップ
- `SelectedSlot.deltaMs`: `sample.timeMillis - idealMs` (ギャップ時は null)

書くこと(表示例):

```kotlin
slots.forEach { slot ->
  val sample = slot.sample
  if (sample == null) {
    // slot.idealMs のギャップ
  } else {
    // sample.timeMillis の点を表示
    // slot.deltaMs で理想時刻との差を見られる
  }
}
```

### `SortOrder` でグリッドの向きを決める

書くこと:

- `OldestFirst`: 時間が前向きに進むターゲットを生成
- `NewestFirst`: 時間が後ろ向きに進むターゲットを生成

得られるもの:

- そのまま描画したい順序(古い -> 新しい / 新しい -> 古い)で結果が返ります。
- `limit` と組み合わせると「直近 15 分を 5 秒間隔で」などの UI を作れます。

### UI では範囲を必ず絞る(無制限スキャンを避ける)

書くこと:

- UI 用途では `fromMillis` と `toMillis` を指定します。

得られるもの:

- `LocationSampleSource.findBetween` が範囲クエリになり、性能/メモリが安定します。

## 注意

- 時間範囲は半開区間 `[from, to)` です。
- `intervalSec == null` の場合は直抽出(グリッドなし)です。
- `intervalSec != null` の場合はグリッドスナップし、欠損は `sample = null` として表現します。
