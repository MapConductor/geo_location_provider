# :storageservice (Room + StorageService)

Gradle モジュール: `:storageservice`

本モジュールは永続化を担当します:

- Room DB (`AppDatabase`) と entity/DAO (本モジュール内に閉じる)
- `StorageService`: 他モジュールが DB にアクセスするための唯一の窓口
- `SettingsRepository`: サンプリング/DR 設定の DataStore 永続化

## レイヤリングルール

他モジュールから DAO 型を直接 import しないでください。`StorageService` を利用します。

## モジュール境界の型(API サーフェス)

他モジュールが利用する想定の主な型:

- `LocationSample`
- `ExportedDay`
- `StorageService`
- `SettingsRepository`

## モジュール境界の契約(何を呼ぶ -> 何が得られるか)

`StorageService` を呼び出すと:

- 並び順や時間範囲のセマンティクスが安定します(後述)
- DB 処理はモジュール内で `Dispatchers.IO` に切り替えられます
- 呼び出し側は DAO やクエリの詳細を知る必要がありません

## 主な API

### `StorageService`

対象: `storageservice/src/main/java/com/mapconductor/plugin/provider/storageservice/StorageService.kt`

よく使う操作:

- `latestFlow(ctx, limit)`: 最新 N 件の Flow (newest-first)
- `latestOnce(ctx, limit)`: 最新 N 件のスナップショット (newest-first)
- `getAllLocations(ctx)`: 全件取得 (昇順、少量データ向け)
- `getLocationsBetween(ctx, from, to, softLimit)`: 範囲取得 `[from, to)` (昇順)
- `getLocationsBetweenDesc(...)` / `getLocationsBeforeDesc(...)`: newest-first 取得(クリップ/ページング向け)
- `insertLocation(ctx, sample)`: 1 件 insert (`DB/TRACE` ログあり)
- `deleteLocations(ctx, items)`: まとめて削除 (空なら no-op)
- `firstSampleTimeMillis(ctx)` / `lastSampleTimeMillis(ctx)`: 診断/スケジューリング
- `ExportedDay` 関連: 日別の export/upload ステータス

#### 並び順と範囲のセマンティクス

- `latestFlow` / `latestOnce`:
  - `timeMillis` 降順(newest-first)
  - 同一時刻の行は provider/id で安定化
- `getLocationsBetween`:
  - 半開区間 `[from, to)`
  - `timeMillis` 昇順(時系列向け)

### `SettingsRepository`

対象: `storageservice/src/main/java/com/mapconductor/plugin/provider/storageservice/prefs/SettingsRepository.kt`

サービス/UI で使う設定フロー:

- `intervalSecFlow(context)`: GPS インターバル(秒)
- `drIntervalSecFlow(context)`: DR インターバル(秒、0 で無効)
- `drGpsIntervalSecFlow(context)`: GPS->DR 投入の間引き(秒、0 は毎回)
- `drModeFlow(context)`: Prediction / Completion

## レシピ(何を呼ぶ -> 何が得られる)

### レシピ: UI で最新 N 件を表示

やること:

- `StorageService.latestFlow(ctx, limit = N)` を ViewModel で collect します。

得られるもの:

- newest-first のリストが新規 insert に追従して更新されます。
- 同一時刻の行があっても安定した順序になります。

### レシピ: 日別エクスポートのための範囲取得

やること:

- ローカル日付を `[fromMillis, toMillis)` に変換して `getLocationsBetween` を呼びます。

得られるもの:

- `timeMillis` 昇順の時系列リスト
- 半開区間 `[from, to)` のため日別スライスに安全です

### レシピ: 全件取得の使い過ぎを避ける

やること:

- UI やワーカーの多くは `latestFlow` や `getLocationsBetween` を優先します。

`getAllLocations` を使うと:

- テーブル全体をメモリに載せるため、少量データ(プレビューやデバッグ、リアルタイムのスナップショット用途)に限定してください。

### レシピ: 夜間バックアップの進捗/状態管理

やること:

- ワーカーで次を呼びます:
  - `ensureExportedDay(epochDay)`
  - `markExportedLocal(epochDay)`
  - `markUploaded(epochDay, fileId)`
  - `markExportError(epochDay, msg)`

得られるもの:

- 日別の export/upload 状態が永続化され、再開や UI 表示に利用できます。

## 典型的な使用例

最新 N 件を購読:

```kotlin
StorageService.latestFlow(ctx, limit = 9).collect { rows ->
  // newest-first
}
```

1 件 insert:

```kotlin
StorageService.insertLocation(ctx, sample)
```

アップロード済みを削除:

```kotlin
StorageService.deleteLocations(ctx, items)
```

## モジュール境界の使い方(詳細: 書き方 -> 結果)

### 日別スライスを正しく作る(タイムゾーン対応)

書くこと:

- 特定タイムゾーンのローカル日付を、半開区間 `[from, to)` のミリ秒へ変換します。

```kotlin
val zoneId = ZoneId.of("Asia/Tokyo")
val day = LocalDate.now(zoneId)
val fromMillis = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
val toMillis = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
val rows = StorageService.getLocationsBetween(ctx, fromMillis, toMillis)
```

得られるもの:

- DST/オフセット変化を含めて、日別の切り出しが正しく行えます。
- `[from, to)` のため隣接日で重複/抜けが発生しにくいです。

### Room を漏らさずに :dataselector へ供給する

書くこと:

- ホスト側で `LocationSampleSource` を実装し、内部で `StorageService` に委譲します。

```kotlin
class StorageSource(private val ctx: Context) : LocationSampleSource {
  override suspend fun findBetween(fromInclusive: Long, toExclusive: Long): List<LocationSample> {
    return StorageService.getLocationsBetween(ctx, fromInclusive, toExclusive)
  }
}
```

得られるもの:

- `:dataselector` は Room/DAO を知らずに pickup/grid ロジックを実行できます。
- 範囲/並び順の契約が end-to-end で保たれます。

### アップロード成功後にだけ DB を drain する

書くこと:

- Drive へのアップロードが成功した後に、アップロードした行だけ削除します。

```kotlin
val uploaded: Boolean = true // uploader の結果
if (uploaded) {
  StorageService.deleteLocations(ctx, rows)
}
```

得られるもの:

- "upload-as-drain" の動作(成功時に削除、失敗時に保持)を安定して実現できます。
- 失敗時は DB に残るためリトライできます。

### ExportedDay で夜間ワーカーの進捗を管理する

書くこと:

- 日付を `epochDay` に変換し、ワーカー実行に合わせて状態を更新します。

```kotlin
val epochDay = day.toEpochDay()
StorageService.ensureExportedDay(ctx, epochDay)
StorageService.markExportedLocal(ctx, epochDay)
// ... upload ...
StorageService.markUploaded(ctx, epochDay, fileId = "drive_file_id")
```

得られるもの:

- ワーカーが日別状態から再開できます。
- UI が raw サンプルを全走査せずに「どこまで終わったか」を表示できます。

## 関連モジュール

- `../core/README.md`
- `../datamanager/README.md`
- `../dataselector/README.md`
