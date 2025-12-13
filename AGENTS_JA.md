# Repository Guidelines (日本語サマリ)

このドキュメントは、GeoLocationProvider リポジトリで作業する際の
**モジュール構成、レイヤ責務、コーディング方針** を日本語で簡潔に
まとめたものです。

詳細な仕様・最新版の正本は英語版 `AGENTS.md` です。
設計を変更する際は必ず `AGENTS.md` を参照し、本ファイルも必要に応じて
併せて更新してください。

---

## モジュール構成と依存関係

- ルート Gradle プロジェクト `GeoLocationProvider` は主に次のモジュールから構成されます:
  - `:app` – Jetpack Compose ベースのサンプル UI  
    (履歴一覧・Pickup・Map 画面・Drive 設定・Upload 設定・手動バックアップなど)。
  - `:core` – Foreground サービス `GeoLocationService` とデバイスセンサー処理、  
    共有設定 (`UploadEngine` など)。永続化は `:storageservice` に委譲し、GNSS は `:gps`、  
    Dead Reckoning は `:deadreckoning` を利用します。
  - `:gps` – GPS 抽象化 (`GpsLocationEngine`, `GpsObservation`,  
    `FusedLocationGpsEngine`)。`FusedLocationProviderClient` と `GnssStatus` を  
    ドメインモデルに変換し、`GeoLocationService` から利用されます。
  - `:storageservice` – Room `AppDatabase` / DAO と `StorageService` ファサード。  
    位置ログ (`LocationSample`) とエクスポート状態 (`ExportedDay`) の唯一の入口です。
  - `:dataselector` – Pickup 用の選択ロジック。`LocationSampleSource` 抽象を通じて  
    `LocationSample` をフィルタし、代表サンプル列 `SelectedSlot` を組み立てます。
  - `:datamanager` – GeoJSON エクスポート・ZIP 圧縮・日次バックアップ  
    (`MidnightExportWorker` / `MidnightExportScheduler`)・Realtime アップロード  
    (`RealtimeUploadManager`)・Drive HTTP クライアント・Drive/Upload 設定の管理を行います。
  - `:deadreckoning` – Dead Reckoning エンジンと公開 API  
    (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`,  
    `DeadReckoningFactory`)。
  - `:auth-appauth` / `:auth-credentialmanager` –  
    `GoogleDriveTokenProvider` の実装を提供する認証モジュール。
  - `mapconductor-core-src` – MapConductor のコアソース (vendored)。  
    基本的には **読み取り専用** とみなし、変更が必要な場合は upstream 寄りで検討します。

- 依存関係 (おおまか):
  - `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`,  
    `:deadreckoning`, `:gps`, 認証モジュール, MapConductor。
  - `:core` → `:gps`, `:storageservice`, `:deadreckoning`。
  - `:datamanager` → `:core`, `:storageservice`, Drive 連携クラス。
  - `:dataselector` → `LocationSampleSource` 抽象のみ  
    (実装は `:app` 側で `StorageService` をラップ)。

---

## StorageService / Room アクセス

- Room (`AppDatabase` / DAO) へのアクセスは **必ず `StorageService` 経由** で行います。
  - 例外は `:storageservice` モジュール内部のみ (DAO 実装と `AppDatabase` 定義)。
  - `:app`, `:core`, `:datamanager`, `:dataselector` から DAO を直接参照しないでください。

- 代表的な API:
  - `latestFlow(ctx, limit)` – `LocationSample` のライブな末尾 `limit` 件 (新しい順)。  
    履歴画面・Map・Realtime Upload から使用されます。
  - `getAllLocations(ctx)` – `timeMillis` 昇順の全件取得。  
    小規模データ向け (Today preview・Realtime 用スナップショットなど)。
  - `getLocationsBetween(ctx, from, to, softLimit)` – `[from, to)` (半開区間) の昇順取得。  
    日次エクスポートや Pickup の時間範囲抽出で使用します。
  - `insertLocation(ctx, sample)` – 1 件挿入。前後の件数と provider / timeMillis を  
    `DB/TRACE` タグでログ出力します。
  - `deleteLocations(ctx, items)` – バッチ削除。空リストの場合は DB に触れません。
  - `lastSampleTimeMillis(ctx)` – 全サンプルの最大 `timeMillis`。  
    バックアップすべき最終日の判定に利用します。
  - `ensureExportedDay`, `oldestNotUploadedDay`, `nextNotUploadedDayAfter`,  
    `exportedDayCount`, `markExportedLocal`, `markUploaded`, `markExportError` –  
    `ExportedDay` テーブルを使った日次バックアップ状態管理に使用します。

- すべての DB 操作は `StorageService` 側で `Dispatchers.IO` 上で実行される想定です。  
  呼び出し側は UI スレッドでブロックしないよう注意してください。

---

## dataselector / Pickup

- `:dataselector` は `LocationSampleSource` 抽象のみに依存し、Room や `StorageService` を知りません。
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)` は  
    半開区間 `[from, to)` を前提とします。

- `SelectorRepository` の方針 (ダイジェスト):
  - `intervalSec == null` の場合:
    - グリッドなしの **直接抽出モード**。昇順取得 → 精度フィルタ → 上限適用 →  
      `SortOrder` に応じた並べ替えのみを行います。
  - `intervalSec != null` の場合:
    - `T = intervalSec * 1000L` とし、±`T/2` のウィンドウ毎に代表サンプル 1 件を  
      選ぶ **グリッドスナップモード**。
    - サンプルが存在しないグリッドは `SelectedSlot(sample = null)` でギャップを表現します。

---

## GeoLocationService / GPS / Dead Reckoning

### 役割の分離

- `GeoLocationService` (`:core`) の責務:
  - `GpsLocationEngine` (`FusedLocationGpsEngine`) からの `GpsObservation` を受け取り、  
    `provider = "gps"` の `LocationSample` として DB に保存。
  - 「GPS hold 値」(受信値とは別のスムージング済み位置) を維持し、  
    Dead Reckoning エンジンへのアンカーとして利用。
  - `DeadReckoning` インスタンスを生成 (`DeadReckoningFactory.create`) し、  
    `start()` / `stop()` / `submitGpsFix()` / `predict()` を呼び出すホストとして振る舞います。
  - DR ティッカーを起動し、最新 GPS fix (`lastFixMillis`) から現在時刻までの  
    予測 `PredictedPoint` を一定間隔で取得し、`provider = "dead_reckoning"` の  
    `LocationSample` として挿入します (重複ガード付き)。
  - `DeadReckoning.isLikelyStatic()` を定期的に読み取り、`DrDebugState` に反映することで、  
    Map 画面のデバッグオーバーレイがエンジン内部の静止判定を表示できるようにします。

- `DeadReckoning` (`:deadreckoning`) の責務:
  - センサー購読・DR 状態・静止判定・物理ガードを内部で完結させること。  
    (GPS との協調ロジックや DB 挿入ポリシーは `GeoLocationService` 側の責務です)
  - `DeadReckoningConfig` により、静止判定の閾値・プロセスノイズ・`velocityGain`・  
    `maxStepSpeedMps`・デバッグログ有無・ウィンドウサイズなどを調整可能にします。

### DR 間隔と DR 無効化

- サンプリング間隔 (`SettingsRepository`) の契約:
  - `intervalSecFlow(context)` – GPS 間隔 (秒) の Flow。最低 1 秒。
  - `drIntervalSecFlow(context)` – DR 間隔 (秒) の Flow。  
    - `0` のときは **「Dead Reckoning 無効 (GPS のみ)」**。  
    - `> 0` のときは最低 1 秒にクランプされます。
  - `currentIntervalMs` / `currentDrIntervalSec` はレガシー利用向けの同期版。  
    新規コードは Flow ベース API を優先します。

- `GeoLocationService` は両 Flow を購読し、値の変化に応じて:
  - GPS 間隔変更 → `updateIntervalMs` を更新し、サービス稼働中なら GPS 更新を再起動。
  - DR 間隔変更 → `applyDrInterval(sec)` を通じて DR ティッカーを開始・停止。  
    `sec <= 0` の場合は DR を無効化し、以後 DR サンプルを挿入しません。

- UI (`IntervalSettingsViewModel`) は:
  - 「GPS interval (sec)」「DR interval (sec)」の 2 つの入力フィールドを持ちます。
  - 検証ルール:
    - GPS: 最低 1 秒にクランプ。
    - DR:
      - `0` → 「DR 無効 (GPS のみ)」として保存。
      - 有効値は `1 <= DR <= floor(GPS / 2)` の範囲に制限。

---

## Export / Upload / 設定

### MidnightExportWorker / MidnightExportScheduler

- `MidnightExportWorker` の役割 (概要):
  - `UploadPrefs.zoneId` (IANA タイムゾーン ID、デフォルト `Asia/Tokyo`) を用いて  
    各日 `[0:00, 24:00)` の範囲を決定し、「昨日まで」のバックログを処理します。
  - `StorageService.ensureExportedDay`, `oldestNotUploadedDay`,  
    `nextNotUploadedDayAfter`, `exportedDayCount`, `lastSampleTimeMillis` を用いて:
    - どの日付を処理するか
    - 進捗・サマリメッセージ (`backupStatus`) を Drive 設定画面用に書き出すか  
    を決定します。
  - 各日について:
    - `StorageService.getLocationsBetween` で 1 日分の `LocationSample` を取得。
    - `GeoJsonExporter` で Downloads に GeoJSON+ZIP を出力。
    - `markExportedLocal` を呼び、ローカル出力済みとしてマーク。
    - Drive 設定 (`DrivePrefsRepository` / `AppPrefs`) から有効なフォルダ ID を解決し、  
      `UploadEngine.KOTLIN` の場合に `UploaderFactory` でアップローダを生成。
    - アップロードの成否に応じて `markUploaded` / `markExportError` を呼び、  
      `backupStatus` に人間可読なサマリを記録。
    - ZIP ファイルは成功・失敗にかかわらず必ず削除。
    - アップロード成功かつその日にレコードが存在する場合のみ、  
      `StorageService.deleteLocations` で該当日のデータを削除。

- 「Backup days before today」:
  - `MidnightExportWorker.runNow(context)` でワーカーを即時実行し、  
    `lastSampleTimeMillis` を基準に最初のサンプル日〜昨日までを再スキャンします。
  - `ExportedDay` がすでに全て uploaded == true の場合でも、  
    LocationSample の実データ範囲に基づいて再処理する設計です。

### RealtimeUploadManager / Upload 設定

- `RealtimeUploadManager` (`:datamanager`) の役割:
  - `UploadPrefsRepository` の Flow (`schedule`, `intervalSec`, `zoneId`) と  
    `SettingsRepository` の Flow (GPS/DR 間隔) を購読。
  - `StorageService.latestFlow(ctx, limit = 1)` で最新 `LocationSample` を監視し、  
    `UploadSchedule.REALTIME` かつ Drive 設定が整っている場合のみ反応します。
  - アップロード間隔の扱い:
    - `intervalSec <= 0` → **「新規サンプルごとにアップロード」**。
    - それ以外は cooldown として機能します。
    - DR が有効な場合は DR 間隔、無効な場合は GPS 間隔を参照し、  
      `intervalSec` がサンプリング間隔と同じ場合も「毎サンプル」とみなします。
  - アップロード処理:
    - `StorageService.getAllLocations` で全レコードを昇順取得。
    - 最新サンプルの時刻と `zoneId` に基づいて  
      `YYYYMMDD_HHmmss.json` 形式のファイル名を決め、`GeoJsonExporter.toGeoJson` の結果を  
      `cacheDir` 配下の JSON ファイルに書き出し。
    - Drive 設定 (`DrivePrefsRepository` の UI フォルダ ID → 空なら `AppPrefs.folderId`) を元に  
      有効なフォルダ ID を解決。
    - `UploaderFactory.create(context, appPrefs.engine)` でアップローダを生成  
      (内部で `DriveTokenProviderRegistry` のトークンプロバイダを優先利用)。
    - JSON をアップロードし、成功した場合のみ対象 `LocationSample` を  
      `StorageService.deleteLocations` で削除。
    - キャッシュファイルは成功・失敗に関わらず削除。

- Upload 設定 UI (`UploadSettingsScreen` / `UploadSettingsViewModel`):
  - Upload on/off:
    - Drive が未設定 (accountEmail 空) の場合は ON にできず、警告メッセージを表示。
  - スケジュール:
    - `NONE` – 自動アップロードなし。
    - `NIGHTLY` – `MidnightExportWorker` のみ。
    - `REALTIME` – `RealtimeUploadManager` が新規サンプルに応じてアップロード。
  - `intervalSec`:
    - `0` → 「毎サンプル」。
    - 1–86400 秒にクランプ (それ以上は 86400 として扱う)。
  - `zoneId`:
    - `Asia/Tokyo` などの IANA タイムゾーン ID。  
    - Nightly export と Today preview の双方で使用されます。

---

## UI / Compose (サマリ)

- `MainActivity`:
  - Activity 直下の `NavHost` を持ち、`"home"`, `"drive_settings"`, `"upload_settings"` を切り替えます。
  - ランタイム権限を `ActivityResultContracts.RequestMultiplePermissions` で取得し、  
    許可後に `GeoLocationService` を起動します。

- `AppRoot`:
  - 内部に `"home"`, `"pickup"`, `"map"` を持つ `NavHost` を持ちます。
  - AppBar:
    - タイトルは現在のルートに応じて「GeoLocation」「Pickup」「Map」を表示。
    - Pickup / Map 画面では戻るボタンを表示。
    - Home では `Map`, `Pickup`, `Drive`, `Upload` ボタンと  
      `ServiceToggleAction` (サービス Start/Stop トグル) を表示します。

- `MapScreen` / `GoogleMapsExample`:
  - MapConductor + Google Maps backend を使用。
  - 上部:
    - `GPS` / `DeadReckoning` チェックボックス。
    - `Count` 入力 (1–5000)。
    - `Apply` / `Cancel` ボタン。
  - `Apply` 押下:
    - チェックボックスと Count をロックし、`Count` 件までの最新サンプルから  
      GPS / DR ごとのポリラインを描画します。
      - GPS: 青・太め・背面。
      - DR : 赤・細め・前面。
    - サンプルは距離ではなく `timeMillis` による **時系列** で接続します。
  - `Cancel` 押下:
    - ポリラインをクリアし、入力をアンロック。カメラ位置・ズームは維持。
  - 右上のデバッグオーバーレイ:
    - `GPS`, `DR`, `ALL` の表示件数 / DB 件数。
    - `DrDebugState` 由来の静止フラグ (`Static: YES/NO`)。
    - GPS/DR 間距離・最新 GPS 精度・エンジン内部の「GPS weight」などを表示。
  - 最新 GPS サンプルの精度円:
    - 中心: 最新 GPS の位置。
    - 半径: `accuracy` [m]。
    - 線は細めの青、塗りは半透明の青。

---

## コーディングポリシー (抜粋)

- すべての **コード** (Kotlin / Java / XML / Gradle スクリプト) は ASCII のみを使用します。
  - コメント・リテラル文字列も含めて、日本語などのマルチバイト文字は使用不可。
- 多言語ドキュメント (日本語 / スペイン語など) は `README_JA.md`,
  `README_ES.md`, `AGENTS_JA.md`, `AGENTS_ES.md` などの Markdown のみで使用します。
- 公開 API には KDoc (`/** ... */`) で役割・設計・契約を明示し、  
  実装詳細には最小限の `//` コメントとシンプルなセクション見出し  
  (`// ---- Section ----` など) を用います。

---

## 公開 API (ライブラリとしての入口)

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`,
    `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`:
  - 認証・トークン: `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - 設定: `DrivePrefsRepository`, `UploadPrefsRepository`, `UploadSchedule`
  - Drive API: `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - エクスポート / アップロード:
    - `UploadEngine` (`:core.config`)、`GeoJsonExporter`,
      `Uploader`, `UploaderFactory`, `RealtimeUploadManager`
  - バックグラウンド:
    - `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`:
  - `GpsLocationEngine`, `GpsObservation`, `FusedLocationGpsEngine`

ここに挙がっていない型は原則として実装詳細 (internal) とみなし、
互換性を保証しない想定で設計してください。

