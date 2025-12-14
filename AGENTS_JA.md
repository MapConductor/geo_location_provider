# Repository Guidelines (日本語サマリ)

このドキュメントは、GeoLocationProvider リポジトリの
**モジュール構成・レイヤ責務・コーディング方針** を日本語で要約したものです。  
正本・最新版は英語版 `AGENTS.md` です。設計変更時は必ず `AGENTS.md` を参照し、
必要に応じて本ファイルも更新してください。

---

## プロジェクト構成と依存関係

- ルート Gradle プロジェクト `GeoLocationProvider` は主に次のモジュールから構成されます。
  - `:app` – Jetpack Compose ベースのサンプル UI  
    （履歴・Pickup・Map・Drive 設定・Upload 設定・手動バックアップ）
  - `:core` – フォアグラウンドサービス `GeoLocationService`、センサー処理、
    共通設定 (`UploadEngine`)。永続化は `:storageservice` に委譲し、
    GNSS は `:gps`、Dead Reckoning は `:deadreckoning` を利用します。
  - `:gps` – `GpsLocationEngine` / `GpsObservation` /
    `FusedLocationGpsEngine` を提供する GPS 抽象レイヤ。
  - `:storageservice` – Room `AppDatabase` / DAO と `StorageService`。  
    `LocationSample`（位置ログ）と `ExportedDay`（日次エクスポート状態）を管理します。
  - `:dataselector` – Pickup 用の選択ロジック。
  - `:datamanager` – GeoJSON エクスポート・ZIP 圧縮・Upload Worker 群・
    Drive / Upload 設定リポジトリ。
  - `:deadreckoning` – Dead Reckoning エンジンと API。
  - `:auth-appauth` / `:auth-credentialmanager` – Drive 用トークンプロバイダ実装。
  - `mapconductor-core-src` – MapConductor コアソース（vendored）。  
    基本的に **読み取り専用** とし、修正は upstream 側で検討します。

- 依存関係（概略）:
  - `:app` → `:core`, `:dataselector`, `:datamanager`,
    `:storageservice`, `:deadreckoning`, `:gps`, 認証モジュール, MapConductor
  - `:core` → `:gps`, `:storageservice`, `:deadreckoning`
  - `:datamanager` → `:core`, `:storageservice`, Drive 連携クラス
  - `:dataselector` → `LocationSampleSource` 抽象のみ  
    （実装は `:app` で `StorageService` をラップ）

---

## StorageService / Room アクセス

- Room (`AppDatabase` / DAO) へのアクセスは **必ず `StorageService` 経由** で行います。
  - 例外は `:storageservice` モジュール内のみ（DAO / DB 実装）。
  - `:app`, `:core`, `:datamanager`, `:dataselector` から DAO を直接参照しないこと。

代表的な API:

- `latestFlow(ctx, limit)` – `LocationSample` の末尾 `limit` 件を新しい順で流す `Flow`  
  履歴画面・Map・Realtime Upload から利用されます。
- `getAllLocations(ctx)` – 全 `LocationSample` を `timeMillis` 昇順で取得。  
  Today preview や Realtime Upload 用のスナップショット向け。
- `getLocationsBetween(ctx, from, to, softLimit)` – 半開区間 `[from, to)` を取得。  
  日次エクスポートや Pickup の範囲抽出で使用します。
- `insertLocation`, `deleteLocations` – 挿入・削除処理（`DB/TRACE` ログ付き）。
- `lastSampleTimeMillis`, `ensureExportedDay`, `oldestNotUploadedDay`,
  `nextNotUploadedDayAfter`, `exportedDayCount`, `markExportedLocal`,
  `markUploaded`, `markExportError` – `ExportedDay` を用いた日次バックアップ状態管理。

すべての DB アクセスは `StorageService` 内で `Dispatchers.IO` 上で実行される想定です。

---

## dataselector / Pickup

- `:dataselector` は Room には依存せず、`LocationSampleSource` 抽象のみを見ます。
- `SelectorRepository` は:
  - `intervalSec == null` – 間引きなし（オプションの精度フィルタ＋ソート）
  - `intervalSec != null` – グリッドスナップ＋代表サンプル選択＋ギャップ表現

Pickup UI (`PickupScreen`) は `SelectorUseCases` を経由してこのレイヤを利用します。

---

## GeoLocationService / GPS / Dead Reckoning

- `GeoLocationService`:
  - `GpsLocationEngine` からの `GpsObservation` を `LocationSample` に変換し、
    provider `"gps"` として保存します。
  - GPS hold 位置を `DeadReckoning` に `GpsFix` として投入します。
  - `SettingsRepository.drIntervalSecFlow` に応じて DR ティッカーを駆動し、
    provider `"dead_reckoning"` の `LocationSample` を生成します。

Dead Reckoning 実装（`:deadreckoning`）は GPS 方向に沿った 1D 状態を持ち、
加速度と GPS 速度から静止 / 移動を推定し、`maxStepSpeedMps` などでスパイクを抑制します。

---

## 設定管理（Sampling / Upload）

- `storageservice.prefs.SettingsRepository`:
  - `intervalSecFlow(context)` – GPS 間隔（秒）
  - `drIntervalSecFlow(context)` – DR 間隔（秒。`0` は DR 無効）

- `datamanager.prefs.UploadPrefsRepository`:
  - `scheduleFlow` – `UploadSchedule.NONE` / `NIGHTLY` / `REALTIME`
  - `intervalSecFlow` – アップロード間隔（秒。0 or 1–86400）
  - `zoneIdFlow` – IANA タイムゾーン ID（例 `"Asia/Tokyo"`）

`UploadSettingsViewModel` はこれらを UI に公開し、Drive 未設定（`accountEmail` 空）の場合は
Upload トグルを ON にできないようにガードします。

---

## Auth / Drive / DriveTokenProviderRegistry

- `GoogleDriveTokenProvider` は Drive アクセストークンの抽象です。
  - `"Bearer "` を付けないトークン文字列を返すこと。
  - 通常のエラーでは `null` を返し、例外を投げないこと。
  - バックグラウンドから UI を開始しないこと。

- 推奨実装:
  - `CredentialManagerTokenProvider` (`:auth-credentialmanager`)
  - `AppAuthTokenProvider` (`:auth-appauth`)

### DriveTokenProviderRegistry（バックグラウンド）

- `DriveTokenProviderRegistry` はバックグラウンド用 `GoogleDriveTokenProvider` を 1 つ保持し、
  - `MidnightExportWorker` / `MidnightExportScheduler`
  - `RealtimeUploadManager`
 から `UploaderFactory` を通じて利用されます。

- サンプルアプリの `App.onCreate()` は:
  - 起動直後に `CredentialManagerAuth.get(this)` を登録（デフォルト）。
  - `DrivePrefs.authMethod` を読み、`"appauth"` かつ AppAuth が認証済みなら
    `AppAuthAuth.get(this)` をバックグラウンドプロバイダとして再登録。
  - その上で `MidnightExportScheduler.scheduleNext(this)` と
    `RealtimeUploadManager.start(this)` を呼びます。

- `DriveSettingsViewModel` は UI 操作に応じてレジストリと設定を更新します。
  - `markCredentialManagerSignedIn()`:
    - `authMethod = "credential_manager"` を保存。
    - `accountEmail` が空なら `"cm_signed_in"` を保存。
    - `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(app))`
      を呼びます。
  - `markAppAuthSignedIn()`:
    - `authMethod = "appauth"` を保存。
    - `accountEmail` が空なら `"appauth_signed_in"` を保存。
    - `DriveTokenProviderRegistry.registerBackgroundProvider(AppAuthAuth.get(app))`
      を呼びます。
- `UploadSettingsViewModel` は `DrivePrefs.accountEmail` が非空であれば
  「Drive 設定済み」とみなし、Upload トグルを ON にできます。

---

## Export / Upload

### MidnightExportWorker / MidnightExportScheduler

- `UploadPrefs.zoneId` からタイムゾーンを決定し、各日 `[00:00, 24:00)` を処理します。
- 各日について:
  - `StorageService.getLocationsBetween(...)` で `LocationSample` を取得。
  - `GeoJsonExporter` で Downloads に `glp-YYYYMMDD.zip` を生成。
  - `markExportedLocal` でローカルエクスポートを記録。
  - Drive フォルダ ID を `DrivePrefs` → `AppPrefs` の順に解決。
  - `UploaderFactory` で Kotlin ベースの uploader を生成し Drive にアップロード。
  - 結果に応じて `markUploaded` / `markExportError` を更新。
  - ZIP は必ず削除し、アップロード成功かつレコードありの場合のみ
    `StorageService.deleteLocations` で当日分の行を削除。

### RealtimeUploadManager / Upload settings

- `UploadPrefsRepository`（スケジュール・間隔・タイムゾーン）と
  `SettingsRepository`（GPS/DR 間隔）を購読します。
- `UploadSchedule.REALTIME` かつ Drive 設定済み（`UploadEngine.KOTLIN` / folderId 有効）のときのみ動作します。
- `StorageService.latestFlow(limit = 1)` から新規サンプルを検知し、`intervalSec` と
  サンプリング間隔から実効クールダウン秒数を計算します。
- アップロード実行時:
  - `StorageService.getAllLocations` で全サンプルを取得。
  - 最新サンプル時刻と `zoneId` に基づき `YYYYMMDD_HHmmss.json` を `cacheDir` に生成。
  - `DrivePrefs` / `AppPrefs` から Drive フォルダ ID を解決。
  - `UploaderFactory.create(context, appPrefs.engine)` で uploader を作成し JSON をアップロード。
  - キャッシュファイルは成功・失敗に関わらず削除。
  - アップロード成功時のみ `StorageService.deleteLocations` でアップロード済み行を削除します。

---

## UI / Compose（概要）

- `MainActivity`
  - Activity レベルの `NavHost` を持ち、`"home"`, `"drive_settings"`,
    `"upload_settings"` を切り替えます。
  - 権限をリクエストし、許可後に `GeoLocationService` を開始します。

- `AppRoot`
  - `"home"`, `"pickup"`, `"map"` を持つ `NavHost` を内部に持ちます。
  - AppBar:
    - 現在のルートに応じてタイトル（GeoLocation / Pickup / Map）を切り替え。
    - Pickup / Map では戻るボタン、Home では `Map` / `Pickup` / `Drive` / `Upload`
      ボタンと `ServiceToggleAction` を表示します。

- `GeoLocationProviderScreen`
  - Interval / DR interval 設定（`IntervalSettingsViewModel`）と履歴リスト
    (`HistoryViewModel`) を表示します。
  - `HistoryViewModel` は `StorageService.latestFlow(limit = 9)` をもとに
    最大 9 件の `LocationSample` を `timeMillis` 降順で保持するメモリ上のバッファを持ちます。
    RealtimeUploadManager や MidnightExportWorker が Room 側の行を削除しても、
    バッファから自然に溢れるまでは履歴リストから急に消えません。

- `PickupScreen`
  - `SelectorUseCases` 経由で Pickup 条件と結果を扱います。

- `MapScreen` / `GoogleMapsExample`
  - MapConductor `GoogleMapsView` + Google Maps backend を用いて、GPS/DR の
    ポリラインとデバッグオーバーレイを描画します。

- `DriveSettingsScreen`
  - 認証方式（Credential Manager / AppAuth）の切り替え、サインイン/サインアウト、
    Drive フォルダ設定、「Backup days before today」「Today preview」アクションを提供します。

- `UploadSettingsScreen`
  - Upload on/off、`UploadSchedule`（NONE/NIGHTLY/REALTIME）、
    `intervalSec`（秒）、`zoneId`（IANA タイムゾーン）を操作し、Drive 未設定時には警告を表示します。

---

## 公開 API（ライブラリとしての入口）

- `:storageservice`
  - `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`,
    `LocationSampleSource`, `SelectorRepository`,
    `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`
  - 認証 / トークン:
    `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - Drive / Upload 設定:
    `DrivePrefsRepository`, `UploadPrefsRepository`, `UploadSchedule`
  - Drive API:
    `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - エクスポート / アップロード:
    `UploadEngine`, `GeoJsonExporter`, `Uploader`, `UploaderFactory`,
    `RealtimeUploadManager`
  - バックグラウンド処理:
    `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`,
    `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`
  - `GpsLocationEngine`, `GpsObservation`, `FusedLocationGpsEngine`

その他の型（DAO や内部エンジン、HTTP ヘルパーなど）は実装詳細（internal）とみなし、
後方互換性は保証されません。

---

## コーディングポリシー（抜粋）

- Kotlin / Java / XML / Gradle などのプロダクションコードは **ASCII のみ** を使用します
  （コメント・文字列リテラルも含む）。
- 日本語 / スペイン語などの多言語テキストは `*.md` ドキュメント
  （`README_JA.md`, `README_ES.md`, `AGENTS_JA.md`, `AGENTS_ES.md` など）でのみ使用します。
- 公開 API には KDoc（`/** ... */`）で役割と契約を明示し、内部実装には最小限の
  `//` コメントとシンプルなセクション見出し（`// ---- Section ----`）を用います。

