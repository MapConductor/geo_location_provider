# :datamanager (エクスポート + アップロード + Drive 連携)

Gradle モジュール: `:datamanager`

本モジュールは、位置データのファイル出力(GeoJSON / GPX)、バックグラウンド処理のスケジューリング(夜間エクスポート、リアルタイムアップロード)、および Google Drive へのアップロードを担当します。認証は差し替え可能なトークンプロバイダ経由で行います。

## モジュール境界の契約(設定する -> 何が起きる)

設定するもの:

- Drive 認証:
  - `GoogleDriveTokenProvider` 実装を用意(`:auth-credentialmanager` または `:auth-appauth`)
  - `DriveTokenProviderRegistry` に登録
- Preferences:
  - `DrivePrefsRepository` (folder id、auth method、account)
  - `UploadPrefsRepository` (schedule、interval、timezone、output format)
- 開始ポイント:
  - `MidnightExportScheduler.scheduleNext(context)`
  - `RealtimeUploadManager.start(context)`

起きること(効果):

- 夜間バックアップ:
  - 日別に Downloads/`GeoLocationProvider` へ ZIP を出力
  - 設定された Drive フォルダにアップロード
  - 日別の upload 状態を `:storageservice` に記録
  - 各試行後に一時ファイル(ZIP)を削除
  - 成功時はその日の `LocationSample` を DB から削除(Drain)
- リアルタイムアップロード:
  - 最新サンプル時刻に基づき cache にスナップショットファイルを生成
  - 設定間隔で Drive にアップロード
  - 成功時はアップロード対象行を DB から削除(Drain)

## 解決ルール(重要)

「何を設定すると何が起きるか」を決めるルールです。

- スケジュールのゲート:
  - `RealtimeUploadManager` は `UploadPrefsRepository.scheduleFlow == REALTIME` のときだけ新規サンプルに反応します。
  - `MidnightExportWorker` は schedule を見てスキップします(ただし `runNow` など force の場合はフルスキャンします)。
- Drive フォルダの解決順:
  - `DrivePrefsRepository.folderIdFlow` (UI/DataStore) を優先。
  - 空なら `AppPrefs.snapshot(context).folderId` (旧 SharedPreferences) にフォールバック。
- Upload engine:
  - `AppPrefs.snapshot(context).engine` を見て `UploadEngine.KOTLIN` のときだけ uploader を作成します。
- 出力ファイル名:
  - 夜間: Downloads/`GeoLocationProvider` に `glp-YYYYMMDD.zip`。
  - リアルタイム: `cacheDir` に `YYYYMMDD_HHmmss.json` / `.gpx` (試行後に削除)。

## レシピ(何を設定する -> 何が得られる)

### レシピ: 夜間バックアップ(日別 ZIP)

やること:

1. バックグラウンド用トークンプロバイダを登録:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

2. Drive フォルダを設定(DataStore を優先。互換のため `AppPrefs` でのフォールバックもあります):

```kotlin
val drivePrefs = DrivePrefsRepository(appContext)
drivePrefs.setFolderId("YOUR_DRIVE_FOLDER_ID")
```

3. スケジュールと出力を設定:

```kotlin
val uploadPrefs = UploadPrefsRepository(appContext)
uploadPrefs.setSchedule(UploadSchedule.NIGHTLY)
uploadPrefs.setOutputFormat(UploadOutputFormat.GEOJSON)
uploadPrefs.setZoneId("Asia/Tokyo")
```

4. スケジューラを開始:

```kotlin
MidnightExportScheduler.scheduleNext(appContext)
```

起きること:

- `MidnightExportWorker` が深夜帯に実行されます(スケジューラのタイミングは固定ゾーンで計算されます)。
- バックログ(昨日まで)を日別に処理し:
  - `StorageService.getLocationsBetween(...)` で取得
  - `glp-YYYYMMDD.zip` を Downloads/`GeoLocationProvider` に出力
  - Drive にアップロード
  - ZIP は試行後に削除
  - アップロード成功時はその日の DB 行を削除

### レシピ: 手動でバックログ実行

```kotlin
MidnightExportWorker.runNow(appContext)
```

起きること:

- 直ちにワーカーが enqueue され、対象日をフルスキャンして処理します。

### レシピ: リアルタイムアップロード(スナップショット + Drain)

やること:

1. スケジュールを `REALTIME` に設定:

```kotlin
val uploadPrefs = UploadPrefsRepository(appContext)
uploadPrefs.setSchedule(UploadSchedule.REALTIME)
uploadPrefs.setIntervalSec(60) // 0 は "every sample"
```

2. プロセス内で 1 回だけ start:

```kotlin
RealtimeUploadManager.start(appContext)
```

起きること:

- 新規サンプルを監視し、条件が満たされると:
  - `cacheDir` に `YYYYMMDD_HHmmss.json` または `.gpx` を生成
  - Drive にアップロード
  - キャッシュファイルを削除
  - 成功時は DB 行を削除

## end-to-end で動かす最小構成(書き方 -> 目に見える結果)

以下を満たすと、内部実装を読まなくても export/upload の動作が確認できるはずです。

### 1) 背景トークンプロバイダを登録する(auth -> Drive アクセス)

書くこと:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

得られるもの:

- worker/manager が UI なしでアクセストークンを取得できます。
- provider が `null` を返す場合、アップロードはスキップされ「未認可」として扱われます。

### 2) UploadEngine を有効化する(engine -> uploader 生成)

書くこと(互換レイヤ。worker/manager が参照):

```kotlin
AppPrefs.saveEngine(context, UploadEngine.KOTLIN)
```

得られるもの:

- engine が `KOTLIN` のときだけ uploader が生成されます(それ以外は仕様でスキップ)。

### 3) Drive フォルダを設定する(folder id -> アップロード先)

書くこと(推奨):

```kotlin
DrivePrefsRepository(context).setFolderId("YOUR_DRIVE_FOLDER_ID")
```

フォールバック(legacy):

```kotlin
AppPrefs.saveFolderId(context, "YOUR_DRIVE_FOLDER_ID_OR_URL")
```

得られるもの:

- 指定フォルダへアップロードされます。空の場合はスキップされます。

### 4) スケジュールを選ぶ(schedule -> いつ動くか)

書くこと:

- Nightly:
  - `UploadPrefsRepository(context).setSchedule(UploadSchedule.NIGHTLY)`
  - `MidnightExportScheduler.scheduleNext(context)` を呼ぶ
- Realtime:
  - `UploadPrefsRepository(context).setSchedule(UploadSchedule.REALTIME)`
  - `RealtimeUploadManager.start(context)` を呼ぶ

得られるもの:

- Nightly: 日別 ZIP が export/upload されます(バックログは基本的に昨日まで)。
- Realtime: DB insert を契機にスナップショットを upload し、成功時に行を削除します。

## トラブルシュート(症状 -> ありがちな原因)

- "何もアップロードされない":
  - provider 未登録、または `getAccessToken()` が `null`
  - folder id が空(DrivePrefs/AppPrefs)
  - `AppPrefs.engine != UploadEngine.KOTLIN`
  - schedule が `NONE`、または scheduler/manager が起動されていない
- "Nightly が動かない":
  - `MidnightExportScheduler.scheduleNext(context)` 未実行、または OS のバックグラウンド制限
- "Realtime が反応しない":
  - `RealtimeUploadManager.start(context)` 未実行、または schedule が `REALTIME` ではない
- "DB が削除されない":
  - 削除はアップロード成功後のみ(upload-as-drain)

## 主な構成要素

### エクスポータ

- `GeoJsonExporter`: `.geojson` または GeoJSON 1 ファイルを含む `.zip` を書き出し
- `GpxExporter`: `.gpx` または GPX 1 ファイルを含む `.zip` を書き出し

対象:

- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/export/GeoJsonExporter.kt`
- `datamanager/src/main/java/com/mapconductor/plugin/provider/geolocation/export/GpxExporter.kt`

### Drive 認証の抽象

- `GoogleDriveTokenProvider`
- `DriveTokenProviderRegistry`

### アップローダ

- `UploaderFactory`

### 設定(Preferences)

- `DrivePrefsRepository`
- `UploadPrefsRepository`

### バックグラウンド処理

- `MidnightExportWorker` / `MidnightExportScheduler`
- `RealtimeUploadManager`

## 注意

- Worker は UI を開始してはいけません。認証が必要な場合、トークンプロバイダは `null` を返し、フォアグラウンド側でサインインを行います。
- エクスポート範囲は半開区間 `[from, to)` で、タイムゾーンはアップロード設定を参照します。
- アップロード用の一時ファイルは各試行後に削除してください。
