# GeoLocationProvider (日本語版)

GeoLocationProvider は、Android 上で **位置情報を記録・保存・エクスポート・アップロード**
するための SDK 兼サンプルアプリです。

バックグラウンドで位置情報を Room データベースに記録し、GeoJSON+ZIP 形式で
エクスポートし、設定に応じて **Google Drive にアップロード** できます。
日次バックアップ (夜間) と、任意で有効にできる Realtime アップロードの両方を
サポートします。

---

## 機能概要

- バックグラウンド位置取得 (GPS / Dead Reckoning の間隔は設定可能)
- Room データベースへの保存 (`LocationSample`, `ExportedDay`)
- GeoJSON + ZIP 形式でのエクスポート
- `MidnightExportWorker` による日次エクスポート (前日までのバックログ処理)
- 「昨日までのバックアップ」「今日のプレビュー」の手動エクスポート
- Pickup 機能 (期間・件数などの条件で代表サンプルを抽出)
- GPS / Dead Reckoning の軌跡を Map 上に可視化
- Google Drive へのアップロード (フォルダ指定・認証診断付き)
- Realtime Upload Manager によるリアルタイムアップロード (間隔・タイムゾーン設定可能)

---

## アーキテクチャ概要

### モジュール構成

本リポジトリはマルチモジュール構成の Gradle プロジェクトです:

- `:app` – Jetpack Compose ベースのサンプル UI  
  (履歴一覧、Pickup 画面、Map 画面、Drive 設定、Upload 設定、手動バックアップなど)。
- `:core` – Foreground サービス `GeoLocationService` とデバイスセンサー処理、  
  共有設定 (`UploadEngine` など)。永続化は `:storageservice` に委譲し、GNSS は `:gps`、  
  Dead Reckoning は `:deadreckoning` を利用します。
- `:gps` – GPS 抽象化レイヤ (`GpsLocationEngine`, `GpsObservation`,  
  `FusedLocationGpsEngine`)。`FusedLocationProviderClient` / `GnssStatus` を  
  安定したドメインモデルに変換します。
- `:storageservice` – Room `AppDatabase` / DAO と `StorageService` ファサード。  
  位置ログ (`LocationSample`) とエクスポート状態 (`ExportedDay`) の唯一の入口です。
- `:dataselector` – Pickup 用の選択ロジック (時間グリッド・間引き・ギャップ表現)。
- `:datamanager` – GeoJSON エクスポート・ZIP 圧縮・日次バックアップ  
  (`MidnightExportWorker` / `MidnightExportScheduler`)・Realtime アップロード  
  (`RealtimeUploadManager`)・Drive HTTP クライアント・Drive/Upload 設定用のリポジトリ。
- `:deadreckoning` – Dead Reckoning エンジンと公開 API  
  (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`,  
  `DeadReckoningFactory`)。
- `:auth-appauth` / `:auth-credentialmanager` –  
  `GoogleDriveTokenProvider` 実装を提供する認証モジュール。

依存関係 (概要):

- `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`,  
  `:deadreckoning`, `:gps`, 認証モジュール。
- `:core` → `:gps`, `:storageservice`, `:deadreckoning`。
- `:dataselector` → `LocationSampleSource` 抽象のみ  
  (実装は `:app` 側で `StorageService` をラップ)。
- `:datamanager` → `:core`, `:storageservice`, Drive 連携クラス。

---

## 主要コンポーネント

### エンティティとストレージ

- `LocationSample` – 位置サンプル 1 件を表します  
  (緯度・経度・タイムスタンプ・速度・バッテリー状態・GNSS 品質など)。
- `ExportedDay` – 日別のエクスポート状態  
  (ローカル出力済み、アップロード結果、最後のエラーなど) を管理します。
- `StorageService` – Room (`AppDatabase` / DAO) への唯一のファサード:
  - `latestFlow(ctx, limit)` – 最新 `limit` 件の `LocationSample` を新しい順で流す Flow。  
    履歴画面、Map 画面、Realtime Upload から利用されます。
  - `getAllLocations(ctx)` – 全 `LocationSample` を `timeMillis` 昇順で取得。  
    Today preview や Realtime Export 用のスナップショットに使用します。
  - `getLocationsBetween(ctx, from, to, softLimit)` – `[from, to)` 半開区間の  
    `LocationSample` を昇順で取得。日次エクスポートや Pickup の範囲抽出に使用。
  - `insertLocation`, `deleteLocations` – 位置サンプルの挿入・削除 (DB/TRACE ログ付き)。
  - `lastSampleTimeMillis`, `ensureExportedDay`, `oldestNotUploadedDay`,  
    `nextNotUploadedDayAfter`, `exportedDayCount`, `markExportedLocal`,  
    `markUploaded`, `markExportError` など、日次バックアップ状態の管理 API を提供します。

### GPS エンジン (`:gps`)

- `FusedLocationGpsEngine`:
  - `FusedLocationProviderClient` と `GnssStatus` のコールバックを購読し、
    `GpsObservation` (lat/lon, accuracy, speed, 進行方向, GNSS (used/total/cn0Mean)) を生成します。
  - `GeoLocationService` は `GpsLocationEngine` インターフェイスに対してのみ依存し、
    `GpsObservation` を `LocationSample(provider="gps")` と Dead Reckoning の fix に変換します。

### Dead Reckoning (`:deadreckoning`)

- 公開 API:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`。
- 実装 (`DeadReckoningImpl`) は internal で、主に次を行います:
  - GPS “hold” 位置列を元に、**直近の GPS 方向に沿った 1D モデル** を構築。
  - `GpsFix` に基づき、内部のアンカー位置と基準速度を再設定。
  - 加速度ウィンドウ (水平成分) と有効速度を用いて静止/移動を判定し、  
    静止中は DR 位置を最新 GPS hold に固定し、速度を 0 に近づけます。
  - `maxStepSpeedMps` による「物理的にありえない速度」ガードで IMU スパイクを破棄。
  - `predict(from, to)` では、指定期間の最新状態スナップショットを返します  
    (初回 GPS アンカー前は空リストを返す場合があります)。

### GeoLocationService (`:core`)

- `GeoLocationService` の役割:
  - `GpsLocationEngine` からの `GpsObservation` を受け取り、`LocationSample(provider="gps")` として保存。
  - 「GPS hold 値」(生の GPS 受信値とは別のスムージング済み位置) を維持し、  
    Dead Reckoning のアンカーとして `submitGpsFix` に渡します。
  - `DeadReckoningFactory.create(applicationContext, config)` でエンジンを生成し、
    `start()` / `stop()` / `submitGpsFix()` / `predict()` を呼び出すホストとして振る舞います。
  - DR ティッカー:
    - 間隔 (秒) は `SettingsRepository.drIntervalSecFlow` から読み取った `drIntervalSec`。
    - `drIntervalSec == 0` → **Dead Reckoning 無効 (GPS のみ)** としてティッカーを停止。
    - `drIntervalSec > 0` → 一定間隔で `dr.predict(fromMillis = lastFixMillis, toMillis = now)` を呼び、
      最後の `PredictedPoint` を `LocationSample(provider="dead_reckoning")` として挿入。
  - 静止フラグ:
    - 各ティックで `dr.isLikelyStatic()` を取得し、`DrDebugState` に書き込みます。
    - Map 画面は `DrDebugState` を購読して `Static: YES/NO` をオーバーレイ表示します。

### Export / Upload (`:datamanager`)

- 日次エクスポート (`MidnightExportWorker` / `MidnightExportScheduler`):
  - `UploadPrefs.zoneId` (IANA タイムゾーン、デフォルト `Asia/Tokyo`) に基づき、
    各日 `[0:00, 24:00)` の区間を計算。
  - `StorageService.ensureExportedDay`, `oldestNotUploadedDay`,  
    `nextNotUploadedDayAfter`, `exportedDayCount`, `lastSampleTimeMillis` を用いて  
    バックログ (「昨日まで」) を順次処理します。
  - 各日の処理:
    - `getLocationsBetween` で 1 日分の `LocationSample` を取得。
    - `GeoJsonExporter` で Downloads に GeoJSON+ZIP (例: `glp-YYYYMMDD.zip`) を出力。
    - `markExportedLocal` でローカル出力済みとしてマーク。
    - Drive 設定 (`DrivePrefsRepository` の UI フォルダ ID / resourceKey → 空なら `AppPrefs.folderId`) から
      実際のフォルダ ID を解決。
    - `UploadEngine.KOTLIN` の場合は `UploaderFactory` を介してアップロード。  
      成功時は `markUploaded`、失敗時は `markExportError` でエラー内容を記録。
    - ZIP ファイルは成功・失敗にかかわらず必ず削除。
    - アップロード成功かつその日にレコードが存在する場合のみ、`deleteLocations` で該当日の行を削除。

- 「Backup days before today」:
  - `MidnightExportWorker.runNow(context)` を通じて、  
    `lastSampleTimeMillis` ベースで最初のサンプル日〜昨日までを強制スキャンします。
  - `ExportedDay.uploaded == true` で埋まっている状態でも、実データ範囲に基づいて再処理されます。

- Realtime Upload (`RealtimeUploadManager`):
  - `UploadPrefsRepository.scheduleFlow`, `intervalSecFlow`, `zoneIdFlow` と  
    `SettingsRepository.intervalSecFlow` / `drIntervalSecFlow` を購読します。
  - `StorageService.latestFlow(ctx, limit = 1)` で最新サンプルを監視し、  
    `UploadSchedule.REALTIME` かつ Drive 設定が整っている場合のみ動作します。
  - アップロード間隔:
    - `intervalSec <= 0` → 各新規サンプルごとにアップロード。
    - それ以外は cooldown として動作。
    - DR 有効時は DR 間隔、無効時は GPS 間隔を参照し、`intervalSec` と一致するときも  
      「毎サンプル」として扱います。
  - アップロード処理:
    - `getAllLocations` で全 `LocationSample` を取得。
    - 最新サンプル時刻と `zoneId` に基づき `YYYYMMDD_HHmmss.json` という名前で  
      GeoJSON を `cacheDir` に書き出し。
    - Drive フォルダ ID を DrivePrefs+AppPrefs から解決。
    - `UploaderFactory.create(context, appPrefs.engine)` でアップローダを生成  
      (内部で `DriveTokenProviderRegistry` を利用してトークンプロバイダを取得)。
    - JSON をアップロードし、成功時は `deleteLocations` でアップロード済みレコードを削除。
    - キャッシュファイルは成功・失敗にかかわらず削除。

### 設定 (Drive / Upload)

- Drive 設定:
  - レガシー: `AppPrefs` (SharedPreferences) – `UploadEngine` / `folderId` など。
  - 新設: `DrivePrefsRepository` (DataStore) – `folderId`, `resourceKey`,
    `accountEmail`, `uploadEngineName`, `authMethod`,
    `tokenUpdatedAtMillis`, `backupStatus` など。
  - `DriveSettingsScreen` / `DriveSettingsViewModel` は基本的に
    `DrivePrefsRepository` を通じて設定を読み書きし、  
    `AppPrefs` にもフォルダ ID / エンジン種別をコピーして後方互換性を維持します。

- Upload 設定:
  - `UploadPrefsRepository` (DataStore) –  
    `UploadSchedule` (`NONE` / `NIGHTLY` / `REALTIME`)、`intervalSec`、`zoneId` を保持。
  - `UploadSettingsScreen` / `UploadSettingsViewModel`:
    - Drive が未設定 (accountEmail 空) の場合は Upload を ON にできないようガード。
    - `UploadSchedule` の変更 (`NONE`, `NIGHTLY`, `REALTIME`)。
    - `intervalSec` の検証とクランプ (0 or 1–86400)。
    - `zoneId` (IANA タイムゾーン ID) の設定。

---

## UI / Compose 構成

- `MainActivity`:
  - Activity 直下の `NavHost` に `"home"`, `"drive_settings"`,
    `"upload_settings"` を持ちます。
  - 権限は `ActivityResultContracts.RequestMultiplePermissions` で要求し、
    許可後に `GeoLocationService` を開始します。

- `AppRoot`:
  - 内部に `"home"`, `"pickup"`, `"map"` のルートを持つ `NavHost` を配置。
  - AppBar:
    - タイトル: 現在のルートに応じて “GeoLocation” / “Pickup” / “Map”。
    - `Pickup` / `Map` では戻るボタンを表示。
    - `home` では `Map`, `Pickup`, `Drive`, `Upload` ボタンと  
      `ServiceToggleAction` (サービス Start/Stop) を表示します。

- `GeoLocationProviderScreen`:
  - Interval / DR interval 設定 (`IntervalSettingsViewModel`) と履歴一覧 (`HistoryViewModel`) を表示します。

- `PickupScreen`:
  - `SelectorUseCases` を通じて Pickup 条件と結果を扱います。

- `MapScreen` / `GoogleMapsExample`:
  - MapConductor (`GoogleMapsView`) + Google Maps backend を使用。
  - 上部 UX:
    - `GPS` / `DeadReckoning` チェックボックス。
    - 表示件数 `Count` (1–5000)。
    - `Apply` / `Cancel` ボタン。
  - `Apply`:
    - 入力をロックし、最新の `LocationSample` から最大 `Count` 件を対象に:
      - GPS: 青い太めのポリライン (背面)。
      - DR : 赤い細めのポリライン (前面)。
    - 接続順は距離ではなく `timeMillis` による時系列順です。
  - `Cancel`:
    - ポリラインを削除し、入力をアンロック。
    - カメラ位置・ズームは維持されます。
  - デバッグオーバーレイ:
    - `GPS`, `DR`, `ALL` の表示件数 / DB 件数。
    - `DrDebugState` による静止フラグ (`Static: YES/NO`)。
    - GPS/DR 間距離・最新 GPS 精度・内部の GPS 重みスケールなど。
  - 精度サークル:
    - 最新 GPS サンプルの位置を中心とし、半径は `accuracy` (m)。
    - 薄い青い線と半透明の塗りで描画します。

---

## 公開ライブラリ API (概要)

ライブラリとして再利用する際は、次のモジュールと型を主な入口として利用します:

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`,
    `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`,
    `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`:
  - 認証/トークン:
    `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - 設定:
    `DrivePrefsRepository`, `UploadPrefsRepository`,
    `UploadSchedule`
  - Drive API:
    `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - エクスポート/アップロード:
    `UploadEngine`, `GeoJsonExporter`, `Uploader`, `UploaderFactory`,
    `RealtimeUploadManager`
  - バックグラウンド:
    `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`:
  - `GpsLocationEngine`, `GpsObservation`,
    `FusedLocationGpsEngine`

ここに挙がっていない型は、原則として internal / 実装詳細とみなし、
将来の互換性は保証されない前提で設計してください。

---

## 開発・設定メモ

- コードは Kotlin / Jetpack Compose を主体とし、Room + DataStore + WorkManager を利用します。
- **コード (Kotlin / Java / XML / Gradle)** は ASCII のみを使用し、
  日本語などのマルチバイト文字は `README_JA.md` や `AGENTS_JA.md` などの
  Markdown ドキュメントにのみ使う方針です。
- Drive 認証・スコープ・リダイレクト URI の設定を変更した場合は、  
  README (EN/JA/ES) 上の記述と Cloud Console の設定が一致していることを確認してください。

