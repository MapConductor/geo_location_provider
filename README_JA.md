# GeoLocationProvider (日本語概要)

GeoLocationProvider は Android 向けの **位置データ記録・保存・エクスポート・アップロード** 用
SDK / サンプルアプリです。

バックグラウンドで取得した位置情報を Room データベースに保存し、GeoJSON もしくは GPX
形式（どちらも ZIP 化）でエクスポートし、設定に応じて **Google Drive** へアップロードできます。

---

## 主な機能

- バックグラウンド位置取得（GPS / Dead Reckoning の間隔を個別に設定可能）
- Room データベース（`LocationSample`, `ExportedDay`）による履歴保存
- GeoJSON / GPX + ZIP 形式でのエクスポート
- `MidnightExportWorker` による日次バックアップ（前日までのバックログ処理）
- 「Backup days before today」「Today preview」による手動バックアップ
- Pickup 機能（期間 / 件数ベースで代表サンプルを抽出）
- GPS / Dead Reckoning の軌跡を Map 画面で可視化
- Google Drive へのアップロード（フォルダ選択・認証診断付き）
- RealtimeUploadManager によるリアルタイムアップロード（間隔・タイムゾーン設定）
- UploadSettings 画面から出力形式（GeoJSON / GPX）を選択可能

---

## モジュール構成（簡易版）

- `:app`  
  Jetpack Compose ベースのサンプル UI  
  （履歴 / Pickup / Map / Drive 設定 / Upload 設定 / 手動バックアップ）
- `:core`  
  フォアグラウンドサービス `GeoLocationService`、センサー処理、共通設定 (`UploadEngine`)。
  永続化は `:storageservice`、GNSS は `:gps`、Dead Reckoning は `:deadreckoning` を使用。
- `:gps`  
  `GpsLocationEngine` / `GpsObservation` / `FusedLocationGpsEngine` など GPS 抽象化。
- `:storageservice`  
  Room `AppDatabase` / DAO と `StorageService`。`LocationSample` / `ExportedDay` を管理。
- `:dataselector`  
  Pickup 用の選択ロジック（`LocationSampleSource` 抽象のみ参照）。
- `:datamanager`  
  GeoJSON / GPX エクスポート、ZIP 圧縮、`MidnightExportWorker` / `MidnightExportScheduler`、
  `RealtimeUploadManager`、Drive HTTP クライアントとアップローダ、Drive / Upload 設定リポジトリ。
- `:deadreckoning`  
  Dead Reckoning エンジンと API (`DeadReckoning`, `GpsFix`, `PredictedPoint` など)。
- `:auth-appauth` / `:auth-credentialmanager`  
  Google Drive 用 `GoogleDriveTokenProvider` 実装。

依存関係の詳細や設計ポリシーは英語版 `README.md` と `AGENTS.md` を参照してください。

---

## Export / Upload の流れ（概要）

### Upload 設定 (`UploadPrefsRepository` / UploadSettingsScreen)

- `schedule` (`UploadSchedule.NONE` / `NIGHTLY` / `REALTIME`)
- `intervalSec`（0 または 1–86400 秒、0 は「毎サンプル」扱い）
- `zoneId`（IANA タイムゾーン。デフォルト `Asia/Tokyo`）
- `outputFormat` (`UploadOutputFormat.GEOJSON` / `GPX`)  
  → UploadSettings 画面で GeoJSON / GPX を切り替え

### MidnightExportWorker

- `UploadPrefs.zoneId` からタイムゾーンを解決し、1 日を `[00:00, 24:00)` として扱う
- `StorageService.getLocationsBetween(...)` で日別レコードを取得
- `UploadOutputFormat` に応じて:
  - GeoJSON: `GeoJsonExporter` が `.geojson` を ZIP に格納
  - GPX    : `GpxExporter` が `.gpx` を ZIP に格納
- ZIP は Downloads/GeoLocationProvider に `glp-YYYYMMDD.zip` として保存
- `markExportedLocal` でローカル出力済みを記録
- Drive 設定（`DrivePrefs` / `AppPrefs`）からフォルダ ID を解決し、
  `UploaderFactory` + Kotlin ベースの uploader で ZIP をアップロード
- 結果は `ExportedDay` (`markUploaded` / `markExportError`) と
  `DrivePrefsRepository.backupStatus` に記録
- ZIP ファイルは必ず削除し、アップロード成功かつデータ有りの場合のみ
  `StorageService.deleteLocations` で当日の行を削除

### RealtimeUploadManager

- `UploadPrefsRepository.scheduleFlow`, `intervalSecFlow`, `zoneIdFlow`,
  `outputFormatFlow` を購読
- `SettingsRepository.intervalSecFlow`, `drIntervalSecFlow` から GPS / DR 間隔を取得
- `StorageService.latestFlow(limit = 1)` で新規サンプルを検知し、
  `UploadSchedule.REALTIME` かつ Drive 設定済みのときのみ動作
- 実効クールダウン（秒）は `intervalSec` とサンプリング間隔から算出  
  （0 またはサンプリング間隔と同じ値の場合は「毎サンプル」扱い）
- アップロード対象になった場合:
  - `StorageService.getAllLocations` で全サンプルを取得
  - `UploadOutputFormat` に応じて
    - GeoJSON: `YYYYMMDD_HHmmss.json`
    - GPX    : `YYYYMMDD_HHmmss.gpx`
    を `cacheDir` に生成（最新サンプルの時刻と `zoneId` に基づく）
  - Drive フォルダを `DrivePrefs` / `AppPrefs` から解決し、
    `UploaderFactory.create(context, appPrefs.engine)` で uploader を生成
  - 生成したファイルをアップロード後、キャッシュファイルを削除
  - アップロード成功時のみ `StorageService.deleteLocations` でアップロード済み行を削除

---

## UI / 設定画面（抜粋）

- DriveSettingsScreen  
  認証方式（Credential Manager / AppAuth）、サインイン / サインアウト、
  Drive フォルダ設定、「Backup days before today」「Today preview」アクションを提供。
  Today preview では当日のデータを ZIP（中身は GeoJSON または GPX）としてエクスポートし、
  オプションで Drive にアップロード（Room データは削除しない）。

- UploadSettingsScreen  
  Upload on/off、`UploadSchedule`（NONE/NIGHTLY/REALTIME）、`intervalSec`、`zoneId` に加え、
  `outputFormat`（GeoJSON / GPX）を編集可能。Drive 未設定時には警告を表示し、
  Upload を有効にできないようガード。

---

## 注意事項

- Kotlin / Java / XML / Gradle などの **プロダクションコード** は ASCII のみを使用します。
- 日本語やスペイン語の説明は `README_JA.md` / `README_ES.md` /
  `AGENTS_JA.md` / `AGENTS_ES.md` などの Markdown ドキュメントに限定します。
- Google Drive 連携（認証方式 / スコープ / redirect URI）を変更した場合は、
  英語版 `README.md` / `AGENTS.md` と本ファイル（および ES 版）を更新してください。

