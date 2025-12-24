# Repository Guidelines (日本語サマリ)

このファイルは GeoLocationProvider リポジトリの **モジュール構成 / レイヤ責務 /
コーディング規約** を日本語でまとめたものです。正本・最新版は英語版
`AGENTS.md` です。設計や仕様を変更した場合は、まず `AGENTS.md` を更新し、
その内容に合わせて本ファイルも更新してください。

---

## プロジェクト構成と依存関係

- ルート Gradle プロジェクト `GeoLocationProvider` は次のモジュールで構成されます。
  - `:app`  
    Jetpack Compose ベースのサンプル UI。履歴 / Pickup / Map / Drive 設定 /
    Upload 設定 / 手動バックアップなどを提供します。
  - `:core`  
    フォアグラウンドサービス `GeoLocationService`、センサー処理、共通設定
    (`UploadEngine`) を含みます。永続化は `:storageservice` に委譲し、
    GNSS は `:gps`、Dead Reckoning は `:deadreckoning` を利用します。
  - `:gps`  
    GPS 抽象レイヤ (`GpsLocationEngine`, `GpsObservation`,
    `FusedLocationGpsEngine`)。
  - `:storageservice`  
    Room `AppDatabase` / DAO と、それらを隠蔽する `StorageService`。  
    位置ログ (`LocationSample`) とエクスポート状態 (`ExportedDay`) を管理します。
  - `:dataselector`  
    Pickup 用の選択ロジック (`LocationSampleSource` 抽象のみ依存)。
  - `:datamanager`  
    GeoJSON / GPX エクスポート、ZIP 圧縮、`MidnightExportWorker` /
    `MidnightExportScheduler`、`RealtimeUploadManager`、Drive HTTP クライアント、
    Drive / Upload 設定リポジトリを含みます。
  - `:deadreckoning`  
    Dead Reckoning エンジンと API (`DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`)。
  - `:auth-appauth` / `:auth-credentialmanager`  
    Drive 向け `GoogleDriveTokenProvider` 実装 (AppAuth / Credential Manager)。
  - `mapconductor-core-src`  
    MapConductor コアソース (vendored)。**基本は読み取り専用** とし、修正が必要な場合は upstream 側で検討します。

高レベルな依存方向 (簡略):

- `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`,
  `:deadreckoning`, `:gps`, 認証モジュール, MapConductor
- `:core` → `:gps`, `:storageservice`, `:deadreckoning`
- `:dataselector` → `LocationSampleSource` 抽象のみ (実装は `:app`)
- `:datamanager` → `:core`, `:storageservice`, Drive 連携

---

## ビルド / テストコマンド (概要)

- ルートディレクトリで実行:
  - `./gradlew :app:assembleDebug`  
    サンプルアプリ (debug) をビルド。
  - `./gradlew :core:assemble` / `./gradlew :storageservice:assemble`  
    ライブラリモジュールをビルド。
  - `./gradlew :deadreckoning:assemble` / `./gradlew :gps:assemble`
  - `./gradlew lint`  
    Android / Kotlin の静的解析。
  - `./gradlew test` / `./gradlew :app:connectedAndroidTest`  
    ユニットテスト / UI テスト。

開発中は Android Studio 経由でのビルド・実行を想定し、コマンドラインは主に CI / 検証用途です。

---

## コーディングスタイル / 命名 / エンコード

- 主な技術スタック: Kotlin, Jetpack Compose, Gradle Kotlin DSL
  - インデントは 4 スペース。
  - ソースファイルの文字コードは UTF-8 を想定。

- パッケージの方針:
  - アプリ / サービス: `com.mapconductor.plugin.provider.geolocation.*`
  - GPS 抽象: `com.mapconductor.plugin.provider.geolocation.gps.*`
  - ストレージ: `com.mapconductor.plugin.provider.storageservice.*`
  - Dead Reckoning:
    `com.mapconductor.plugin.provider.geolocation.deadreckoning.*`

- 命名規則:
  - クラス / オブジェクト / インターフェース: PascalCase
  - 関数 / 変数 / プロパティ: camelCase
  - 定数: UPPER_SNAKE_CASE
  - 画面レベルの Composable: `...Screen`
  - ViewModel: `...ViewModel`
  - Worker / Scheduler: `...Worker` / `...Scheduler`

- コメントとエンコード:
  - **プロダクションコード** (Kotlin / Java / XML / Gradle) は ASCII のみを使用。
    - コメント / 文字列リテラルも含めてマルチバイト文字は禁止。
  - 多言語テキスト (日本語 / スペイン語) は `README_JA.md` / `README_ES.md` /
    `AGENTS_JA.md` / `AGENTS_ES.md` などの Markdown に限定。
  - 公開 API には KDoc (`/** ... */`) を使い、実装詳細には簡潔な `//` コメントと
    一貫したセクション見出し (`// ---- Section ----`) を用いる。

---

## StorageService / Room アクセス

- Room (`AppDatabase` / DAO) へのアクセスは **必ず `StorageService` 経由** とします。
  - 例外は `:storageservice` モジュール内部のみ (DAO 実装 / DB 初期化など)。
  - `:app`, `:core`, `:datamanager`, `:dataselector` から DAO を直接参照しないこと。

代表的な API:

- `latestFlow(ctx, limit)`  
  末尾 `limit` 件の `LocationSample` を「新しい順」で流す `Flow<List<LocationSample>>`。  
  履歴画面 / Map / realtime upload で使用。
- `getAllLocations(ctx)`  
  すべての `LocationSample` を `timeMillis` 昇順で取得 (小さめのデータセット向け)。
- `getLocationsBetween(ctx, from, to, softLimit)`  
  半開区間 `[from, to)` の `LocationSample` を `timeMillis` 昇順で取得。  
  MidnightExportWorker / Pickup など日付・時間帯ベースの処理に使用。
- `insertLocation`, `deleteLocations`  
  挿入・削除処理。`DB/TRACE` ログで前後の件数を出力。
- `ensureExportedDay`, `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,
  `exportedDayCount`, `markExportedLocal`, `markUploaded`, `markExportError`  
  `ExportedDay` を用いた日次バックアップ状態管理。

すべての DB アクセスは `StorageService` 内で `Dispatchers.IO` 上に載る前提です。

---

## GeoLocationService / Dead Reckoning / 設定

- `GeoLocationService`（`:core`）は次の要素をまとめるフォアグラウンドサービスです。
  - `GpsLocationEngine` / `FusedLocationGpsEngine` による GPS 取得
  - `DeadReckoning`（`:deadreckoning` モジュール）の呼び出し
  - バッテリー・方位などの補助センサー

- Dead Reckoning の設定は `storageservice.prefs.SettingsRepository` で管理されます。
  - `intervalSecFlow(context)` / `currentIntervalMs(context)`  
    ↪ GPS サンプリング間隔 (秒 / ミリ秒)。最小 1 秒。
  - `drIntervalSecFlow(context)` / `currentDrIntervalSec(context)`  
    ↪ DR 間隔 (秒)。`0` は「DR 無効 (GPS のみ)」、`> 0` は 1 秒以上にクランプ。
  - `drModeFlow(context)` / `currentDrMode(context)`  
    ↪ DR モード (`DrMode.Prediction` / `DrMode.Completion`)。

- モード別の DR 挙動 (概要):
  - `drIntervalSec == 0`  
    ↪ Dead Reckoning は完全に無効になり、`dead_reckoning` サンプルは生成されません。
  - `drIntervalSec > 0` かつ `DrMode.Prediction`  
    ↪ 内部のティッカーが定期的に `dr.predict(fromMillis, toMillis)` を呼び出し、その区間の最後の
      `PredictedPoint` を `LocationSample` (provider=`"dead_reckoning"`) として **リアルタイム** に挿入します。
  - `drIntervalSec > 0` かつ `DrMode.Completion`  
    ↪ ティッカーは停止し、新しい GPS フィックスが入るたびに前回〜今回の区間を
      `dr.predict(fromMillis = lastFixMillis, toMillis = now)` で取得し、
      終点が最新の GPS hold 位置に重なるよう線形ブレンドした `dead_reckoning` サンプル群で
      GPS–GPS 間を「補完」します (後ろ向きのブリッジ)。

- `GeoLocationService` は以下の Flow を購読します。
  - `intervalSecFlow`  
    ↪ GPS 間隔の変更があれば、サービス稼働中に `gpsEngine.updateInterval(...)` を再適用。
  - `drIntervalSecFlow`  
    ↪ `applyDrInterval` / `updateDrTicker` から DR 全体の有効/無効・ティッカー開始/停止を制御。
  - `drModeFlow`  
    ↪ Prediction / Completion の切り替えに応じて `updateDrTicker` を呼び直し、
      ティッカー状態とモードを一貫させます。

- `IntervalSettingsViewModel` (home 画面の Interval 設定):
  - GPS 間隔 / DR 間隔のテキストフィールド、DR モード (予測 / 補完) のトグル、
    「Save & Apply」ボタンを提供します。
  - DR 間隔は `[0, floor(GPS / 2)]` 秒の範囲でバリデーションされます。
    - `0` のときは「DR disabled (GPS only)」というトーストを出し、DR を無効化。
    - モード値 (`DrMode`) は保存されるため、次に DR を有効化したとき再利用されます。

---

## Export / Upload と UploadPrefs

### UploadPrefsRepository / UploadSettings

- `UploadPrefsRepository` は DataStore ベースの Upload 設定を公開します。
  - `scheduleFlow` (`UploadSchedule.NONE` / `NIGHTLY` / `REALTIME`)
  - `intervalSecFlow` (0 または 1〜86400 秒)
  - `zoneIdFlow` (IANA タイムゾーン。デフォルトは `Asia/Tokyo`)
  - `outputFormatFlow` (`UploadOutputFormat.GEOJSON` / `GPX`)

- `UploadSettingsScreen` / `UploadSettingsViewModel` は上記を編集する UI を提供します。
  - Upload on/off (Drive 未設定時は ON にできないようガード)
  - 夜間バックアップ vs リアルタイム Upload
  - Realtime 用の間隔とタイムゾーン
  - 出力形式 (GeoJSON / GPX)

### MidnightExportWorker / MidnightExportScheduler

- `MidnightExportWorker` は「前日までのバックログ」を処理します。
  - タイムゾーンは `UploadPrefs.zoneId` から決定 (デフォルト `Asia/Tokyo`)。
  - 1 日は `[00:00, 24:00)` の半開区間として扱います。
  - `StorageService.ensureExportedDay` / `oldestNotUploadedDay` /
    `nextNotUploadedDayAfter` / `exportedDayCount` / `lastSampleTimeMillis`
    などを用いて処理対象の日付を決定し、状態文字列を更新します。
  - 各日について:
    - `StorageService.getLocationsBetween` で当日のレコードを取得。
    - `UploadOutputFormat` に応じて
      - GeoJSON: `GeoJsonExporter` で `.geojson` を生成し ZIP に格納。
      - GPX    : `GpxExporter` で `.gpx` を生成し ZIP に格納。
      - ZIP は Downloads/GeoLocationProvider に `glp-YYYYMMDD.zip` として保存。
    - `markExportedLocal` でローカル出力済みを記録。
    - Drive 設定 (`DrivePrefs`) とレガシー `AppPrefs` から Drive フォルダ ID を解決。
    - `UploaderFactory` + `UploadEngine.KOTLIN` で uploader を作成し ZIP をアップロード。
    - 結果は `markUploaded` / `markExportError` と
      `DrivePrefsRepository.backupStatus` に記録。
    - ZIP ファイルは常に削除し、アップロード成功かつレコードありの場合のみ
      `StorageService.deleteLocations` でその日の行を削除。

### RealtimeUploadManager

- `RealtimeUploadManager` は新しい `LocationSample` を監視し、Upload 設定に応じて
  GeoJSON または GPX をリアルタイムでアップロードします。
  - `UploadPrefsRepository.scheduleFlow`, `intervalSecFlow`, `zoneIdFlow`,
    `outputFormatFlow` を購読。
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
      を `cacheDir` に生成 (最新サンプルの時刻と `zoneId` に基づく)。
    - Drive フォルダ ID を `DrivePrefs` / `AppPrefs` から解決し、
      `UploaderFactory.create(context, appPrefs.engine)` で uploader を生成。
    - 生成したファイルをアップロード後、キャッシュファイルを削除。
    - アップロード成功時のみ `StorageService.deleteLocations` でアップロード済み行を削除。

Today preview (Drive 設定画面からの当日プレビュー) も `UploadPrefs.zoneId` と
同じタイムゾーンを使用し、ZIP の中身は `UploadOutputFormat` に従って GeoJSON または
GPX になります。Room のデータは削除しません。

---

## UI / Compose の要点

- `MainActivity`  
  `"home"`, `"drive_settings"`, `"upload_settings"` を持つ `NavHost` をホストし、
  権限リクエスト後に `GeoLocationService` を開始します。

- `AppRoot`  
  `"home"`, `"pickup"`, `"map"` を持つ内側 `NavHost` を持ちます。AppBar では
  ルートに応じて GeoLocation / Pickup / Map のタイトルと戻るボタン / アクションを切り替えます。

- `GeoLocationProviderScreen`  
  Interval 設定 (`IntervalSettingsViewModel`) と履歴表示 (`HistoryViewModel`) を配置。
  履歴は `StorageService.latestFlow(limit = 9)` を用いたバッファで管理され、
  Upload による削除と一時的に独立して表示されます。

- `PickupScreen`  
  `SelectorUseCases` (dataselector) を用いた Pickup 条件入力と結果一覧。

- `MapScreen` / `GoogleMapsExample`  
  MapConductor の `GoogleMapsView` を利用し、GPS / DR / 混合軌跡、多様なカーブモード、
  DR–GPS の距離 / static フラグ / GPS 精度などのデバッグ情報を表示します。

- `DriveSettingsScreen`  
  Credential Manager / AppAuth 切り替え、サインイン / サインアウト、Drive フォルダ設定、
  「Backup days before today」「Today preview」アクションなどを提供します。

- `UploadSettingsScreen`  
  Upload on/off、`UploadSchedule`（NONE / NIGHTLY / REALTIME）、`intervalSec`、`zoneId`、
  `outputFormat`（GeoJSON / GPX）を編集し、Drive 未設定時には Upload を有効化できないように
  ガードします。

---

## 公開 API サーフェス (ライブラリとしての利用)

英語版 `AGENTS.md` の「Public API Surface」と同じものを、日本語で要約します。
ライブラリとして再利用する場合、基本的には以下のみを直接参照してください。

- `:storageservice`
  - `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`,
    `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`
  - 認証 / トークン:
    `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - 設定:
    `DrivePrefsRepository`, `UploadPrefsRepository`, `UploadSchedule`
  - Drive API:
    `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - エクスポート / アップロード:
    `UploadEngine`, `GeoJsonExporter`, `GpxExporter`,
    `Uploader`, `UploaderFactory`, `RealtimeUploadManager`
  - バックグラウンドジョブ:
    `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`
  - `GpsLocationEngine`, `GpsObservation`, `FusedLocationGpsEngine`

上記以外の型 (DAO, 内部エンジン, HTTP ヘルパーなど) は実装詳細として扱い、
直接依存しないようにしてください (将来の変更対象です)。

---

## セキュリティ / 認証 / その他の注意点

- `local.properties`, `secrets.properties`, `google-services.json` など
  機密情報を含むファイルはリポジトリにコミットしないでください。
- Google 認証 / Drive 連携まわりの挙動を変更した場合は、OAuth スコープや
  redirect URI を Cloud Console と README (EN/JA/ES) 側と合わせる必要があります。
- AppAuth と Credential Manager では **別々のクライアント ID** を使用します。
  - Credential Manager: `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (web/server client)
  - AppAuth          : `APPAUTH_CLIENT_ID` (installed app + custom URI scheme)

以上を守ることで、各モジュールの責務を崩さずに安全な変更が行えます。*** End Patch***"}]}}
