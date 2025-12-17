# Repository Guidelines (日本語サマリ)

このファイルは GeoLocationProvider リポジトリの **モジュール構成・レイヤ責務・
コード規約** を日本語で簡潔にまとめたものです。正本・最新版は英語版
`AGENTS.md` です。設計変更時はまず `AGENTS.md` を更新し、その内容を踏まえて
本ファイルも更新してください。

---

## モジュール構成と依存関係（概要）

- ルート Gradle プロジェクト `GeoLocationProvider` は主に次のモジュールから構成されます。
  - `:app`  
    Jetpack Compose ベースのサンプル UI  
    （履歴、Pickup、Map、Drive 設定、Upload 設定、手動バックアップ）
  - `:core`  
    フォアグラウンドサービス `GeoLocationService`、センサー処理、
    共通設定 (`UploadEngine`)。永続化は `:storageservice` に委譲し、
    GNSS は `:gps`、Dead Reckoning は `:deadreckoning` を利用します。
  - `:gps`  
    `GpsLocationEngine` / `GpsObservation` / `FusedLocationGpsEngine` などの
    GPS 抽象レイヤ。
  - `:storageservice`  
    Room `AppDatabase` / DAO と `StorageService`。位置ログ (`LocationSample`) と
    エクスポート状態 (`ExportedDay`) を管理します。
  - `:dataselector`  
    Pickup 向けの選択ロジック。`LocationSampleSource` 抽象のみ参照します。
  - `:datamanager`  
    GeoJSON / GPX エクスポート、ZIP 圧縮、`MidnightExportWorker` /
    `MidnightExportScheduler`、`RealtimeUploadManager`、Drive HTTP クライアントと
    アップローダ、Drive / Upload 設定リポジトリを含みます。
  - `:deadreckoning`  
    Dead Reckoning エンジンと API (`DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`)。
  - `:auth-appauth` / `:auth-credentialmanager`  
    Google Drive 用 `GoogleDriveTokenProvider` 実装。
  - `mapconductor-core-src`  
    MapConductor コアソース（vendored）。**基本は読み取り専用** とし、
    修正が必要な場合は upstream 側で検討します。

依存関係の向き（簡略）:

- `:app` → `:core`, `:dataselector`, `:datamanager`,
  `:storageservice`, `:deadreckoning`, `:gps`, 認証モジュール, MapConductor
- `:core` → `:gps`, `:storageservice`, `:deadreckoning`
- `:dataselector` → `LocationSampleSource` 抽象のみ  
  （実装は `:app` 側で `StorageService` をラップ）
- `:datamanager` → `:core`, `:storageservice`, Drive 連携クラス

---

## StorageService / Room アクセス

- Room (`AppDatabase` / DAO) へのアクセスは **必ず `StorageService` 経由** で行います。
  - 例外は `:storageservice` モジュール内部のみ（DAO 実装・DB 初期化など）。
  - `:app`, `:core`, `:datamanager`, `:dataselector` から DAO を直接参照しないこと。

代表的な API（抜粋）:

- `latestFlow(ctx, limit)`  
  末尾 `limit` 件の `LocationSample` を新しい順に流す `Flow`。履歴画面・Map・
  Realtime Upload などで使用。
- `getAllLocations(ctx)`  
  すべての `LocationSample` を `timeMillis` 昇順で取得。RealtimeUploadManager や
  Today preview 用のスナップショット向け（巨大データには非推奨）。
- `getLocationsBetween(ctx, from, to, softLimit)`  
  半開区間 `[from, to)` の範囲を `timeMillis` 昇順で取得。日次エクスポートや
  Pickup（日付範囲指定）で使用。
- `insertLocation`, `deleteLocations`  
  挿入・削除処理。`DB/TRACE` ログを書きます。
- `ensureExportedDay`, `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,
  `exportedDayCount`, `markExportedLocal`, `markUploaded`, `markExportError`  
  `ExportedDay` を用いた日次バックアップ状態管理。

すべての DB アクセスは `StorageService` 内で `Dispatchers.IO` 上に載る前提です。

---

## Export / Upload と UploadPrefs

### UploadPrefsRepository / UploadSettings

- `UploadPrefsRepository` は DataStore ベースの Upload 設定を公開します。
  - `scheduleFlow` (`UploadSchedule.NONE` / `NIGHTLY` / `REALTIME`)
  - `intervalSecFlow`（0 または 1–86400 秒）
  - `zoneIdFlow`（IANA タイムゾーン。デフォルト `Asia/Tokyo`）
  - `outputFormatFlow` (`UploadOutputFormat.GEOJSON` / `GPX`)
- `UploadSettingsScreen` / `UploadSettingsViewModel` は上記を編集する UI を提供します。
  - Upload on/off（Drive 設定済みでないと ON にできない）
  - 夜間バックアップ vs リアルタイム Upload
  - Realtime 用の間隔（秒）とタイムゾーン
  - 出力形式（GeoJSON / GPX）

### MidnightExportWorker / MidnightExportScheduler

- `MidnightExportWorker` は「前日までのバックログ」を処理します。
  - タイムゾーンは `UploadPrefs.zoneId` から決定（`Asia/Tokyo` がデフォルト）。
  - 1 日は `[00:00, 24:00)` として扱います。
  - `StorageService.ensureExportedDay` / `oldestNotUploadedDay` /
    `nextNotUploadedDayAfter` / `exportedDayCount` / `lastSampleTimeMillis`
    などを用いて処理対象の日付を決定し、状態文字列を更新します。
  - 各日について:
    - `StorageService.getLocationsBetween` で当日のレコードを取得。
    - `UploadOutputFormat` に応じて `GeoJsonExporter`（GeoJSON）または
      `GpxExporter`（GPX）で ZIP を Downloads/GeoLocationProvider に出力  
      （ファイル名は `glp-YYYYMMDD.zip`、中身は `.geojson` もしくは `.gpx` 1 ファイル）。
    - `markExportedLocal` でローカル出力済みを記録。
    - UI 側の Drive 設定 (`DrivePrefs`) とレガシーな `AppPrefs` から Drive フォルダ ID を解決。
    - `UploaderFactory` + `UploadEngine.KOTLIN` で uploader を作成し ZIP をアップロード。
    - 結果は `markUploaded` / `markExportError` と `DrivePrefsRepository.backupStatus`
      に記録。
    - ZIP ファイルは常に削除し、アップロード成功かつレコードありの場合のみ
      `StorageService.deleteLocations` でその日の行を削除。

### RealtimeUploadManager

- `RealtimeUploadManager` は新しい `LocationSample` を監視し、Upload 設定に応じて
  GeoJSON または GPX をリアルタイムでアップロードします。
  - `UploadPrefsRepository.scheduleFlow`, `intervalSecFlow`,
    `zoneIdFlow`, `outputFormatFlow` を購読。
  - `SettingsRepository.intervalSecFlow`, `drIntervalSecFlow` から
    GPS / DR のサンプリング間隔を取得。
  - `StorageService.latestFlow(limit = 1)` で新規サンプルを監視し、
    `UploadSchedule.REALTIME` かつ Drive 設定済みのときのみ動作。
  - `intervalSec` が 0 またはサンプリング間隔と同じ場合は「毎サンプル」扱いにして
    クールダウンなしでアップロード。
  - アップロード対象になった場合:
    - `StorageService.getAllLocations` で全サンプルを取得。
    - `UploadOutputFormat` に応じて
      - GeoJSON: `YYYYMMDD_HHmmss.json`
      - GPX    : `YYYYMMDD_HHmmss.gpx`
      を `cacheDir` に生成（最新サンプルの時刻と `zoneId` に基づく）。
    - Drive フォルダ ID を `DrivePrefs` / `AppPrefs` から解決し、
      `UploaderFactory.create(context, appPrefs.engine)` で uploader を生成。
    - 生成したファイルをアップロード後、キャッシュファイルを削除。
    - アップロード成功時のみ `StorageService.deleteLocations` でアップロード済み行を削除。

Today preview（Drive 設定画面からの当日プレビュー）も `UploadPrefs.zoneId` と
同じタイムゾーンを使用し、ZIP の中身は `UploadOutputFormat` に従って GeoJSON または
GPX になります。Room のデータは削除しません。

---

## UI / Compose（要点）

- `MainActivity`  
  `"home"`, `"drive_settings"`, `"upload_settings"` を持つ `NavHost` をホストし、
  権限リクエスト後に `GeoLocationService` を開始します。

- `AppRoot`  
  `"home"`, `"pickup"`, `"map"` を持つ `NavHost` を内部に持ちます。AppBar では
  ルートに応じて GeoLocation / Pickup / Map のタイトルと戻るボタン / アクションを切り替えます。

- `DriveSettingsScreen`  
  認証方式（Credential Manager / AppAuth）、サインイン / サインアウト、
  Drive フォルダ設定、「Backup days before today」「Today preview」アクションを提供します。

- `UploadSettingsScreen`  
  Upload on/off、`UploadSchedule`（NONE / NIGHTLY / REALTIME）、`intervalSec`、`zoneId`、
  `outputFormat`（GeoJSON / GPX）を編集し、Drive 未設定時には Upload を有効化できないように
  ガードします。

---

## コーディングポリシー（抜粋）

- Kotlin / Java / XML / Gradle などの **プロダクションコード** は ASCII のみを使用します。
  - コメントや文字列リテラルも含めてマルチバイト文字は禁止です。
- 日本語 / スペイン語などの多言語テキストは `README_JA.md`, `README_ES.md`,
  `AGENTS_JA.md`, `AGENTS_ES.md` などの Markdown ドキュメントにのみ記載します。
- 公開 API には KDoc（`/** ... */`）で役割と契約を記述し、実装詳細は `internal` として
  隠蔽します。
- セクション見出しは `// ---- Section ----` のようなシンプルで一貫したスタイルを使います。

