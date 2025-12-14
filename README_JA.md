# GeoLocationProvider (日本語版)

GeoLocationProvider は、Android 上で **位置データを記録・保存・エクスポート・アップロード**
するための SDK 兼サンプルアプリです。  
バックグラウンドで取得した位置を Room データベースに保存し、GeoJSON+ZIP 形式で
エクスポートし、設定に応じて **Google Drive** にアップロードできます。

---

## 機能概要

- バックグラウンド位置取得（GPS / Dead Reckoning の間隔を個別に設定可能）
- Room データベース (`LocationSample`, `ExportedDay`) への保存
- GeoJSON + ZIP 形式でのエクスポート
- `MidnightExportWorker` による日次バックアップ（前日まで）
- 「Backup days before today」「Today preview」による手動バックアップ
- Pickup 機能（期間 / 件数条件で代表サンプルを抽出）
- GPS / Dead Reckoning の軌跡を Map 画面で可視化
- Google Drive へのアップロード（フォルダ設定・認証診断付き）
- RealtimeUploadManager によるリアルタイムアップロード（間隔・タイムゾーン設定）

---

## モジュール構成（概要）

- `:app` – Jetpack Compose ベースのサンプル UI  
  （履歴、Pickup、Map、Drive 設定、Upload 設定、手動バックアップ）
- `:core` – フォアグラウンドサービス `GeoLocationService`、センサー処理、
  共通設定 (`UploadEngine`)。永続化は `:storageservice` に委譲し、GNSS は `:gps`、
  Dead Reckoning は `:deadreckoning` を利用します。
- `:gps` – `GpsLocationEngine` / `GpsObservation` / `FusedLocationGpsEngine`。
- `:storageservice` – Room `AppDatabase` / DAO と `StorageService`。
- `:dataselector` – Pickup 用の選択ロジック。
- `:datamanager` – エクスポート・アップロード処理、Drive/Upload 設定、
  `MidnightExportWorker` / `MidnightExportScheduler` /
  `RealtimeUploadManager`。
- `:deadreckoning` – Dead Reckoning エンジンと API。
- `:auth-appauth` / `:auth-credentialmanager` – Drive 用トークンプロバイダ実装。

---

## ストレージとエンティティ

- `LocationSample` – 緯度・経度・精度・速度・バッテリ状態・GNSS 品質などを持つ位置レコード。
- `ExportedDay` – 日別のエクスポート状態（ローカル出力済み / アップロード結果 / エラー）。
- `StorageService` – Room への唯一のファサード:
  - `latestFlow(ctx, limit)` – 末尾 `limit` 件を新しい順で流す `Flow`。
  - `getAllLocations(ctx)` – 全 `LocationSample` を `timeMillis` 昇順で取得。
  - `getLocationsBetween(ctx, from, to, softLimit)` – `[from, to)` の範囲を取得。
  - `ensureExportedDay`, `markExportedLocal`, `markUploaded`, `markExportError`
    などで `ExportedDay` を管理。

---

## Export / Upload の流れ

### 日次バックアップ（MidnightExportWorker）

1. `UploadPrefs.zoneId` からタイムゾーンを解決し、当日 `[00:00, 24:00)` を 1 日とみなす。
2. `StorageService.getLocationsBetween(...)` でその日の `LocationSample` を取得。
3. `GeoJsonExporter` で Downloads に `glp-YYYYMMDD.zip` を生成。
4. `DrivePrefs` → `AppPrefs` の順で Drive フォルダ ID を解決。
5. `UploaderFactory` 経由で Kotlin ベースの uploader を作成し Drive にアップロード。
6. 結果を `markUploaded` / `markExportError` に記録し、ZIP は必ず削除。
7. アップロード成功かつレコードありの場合のみ `StorageService.deleteLocations` で当日分を削除。

### RealtimeUploadManager

- `UploadPrefsRepository`（スケジュール・間隔・タイムゾーン）と
  `SettingsRepository`（GPS/DR 間隔）を購読。
- `UploadSchedule.REALTIME` かつ Drive 設定済みのときのみ動作。
- `StorageService.latestFlow(limit = 1)` から新規サンプルを検知し、`intervalSec` と
  サンプリング間隔から実効クールダウン秒数を計算。
- アップロード対象になった場合:
  - `getAllLocations` で全サンプルを取得。
  - 最新サンプルの時刻と `zoneId` から `YYYYMMDD_HHmmss.json` を `cacheDir` に生成。
  - `DrivePrefs` / `AppPrefs` から Drive フォルダ ID を解決。
  - `UploaderFactory.create(context, appPrefs.engine)` で uploader を作成し JSON をアップロード。
  - キャッシュファイルは成功・失敗にかかわらず削除。
  - アップロード成功時のみ `StorageService.deleteLocations` でアップロード済み行を削除。

---

## Drive 認証と DriveTokenProviderRegistry

- すべての Drive アップロードは `GoogleDriveTokenProvider` を通じてアクセストークンを取得します。
  - 実装は `"Bearer "` を付けないトークン文字列を返し、エラー時は `null` を返します。
  - バックグラウンドから UI を起動してはいけません。

### 認証方式

- Credential Manager (`:auth-credentialmanager`)
  - `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` を用いるサーバークライアント ID。
- AppAuth (`:auth-appauth`)
  - `APPAUTH_CLIENT_ID` を用いるインストールアプリ用クライアント ID と
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect` リダイレクト。

### DriveTokenProviderRegistry の配線

- `DriveTokenProviderRegistry` はバックグラウンド用トークンプロバイダを 1 つ保持し、
  `MidnightExportWorker` / `RealtimeUploadManager` から `UploaderFactory` 経由で利用されます。
- サンプルアプリの `App.onCreate()` は:
  - 起動時に `CredentialManagerAuth.get(this)` を登録（デフォルト）。
  - `DrivePrefs.authMethod` を読み、`"appauth"` かつ AppAuth が認証済みなら
    `AppAuthAuth.get(this)` をバックグラウンドプロバイダとして再登録。
  - その上で `MidnightExportScheduler.scheduleNext(this)` と
    `RealtimeUploadManager.start(this)` を呼び出します。
- `DriveSettingsViewModel`:
  - `markCredentialManagerSignedIn()` – `authMethod = "credential_manager"` と
    `accountEmail = "cm_signed_in"`（未設定時）を保存し、レジストリに
    `CredentialManagerAuth.get(app)` を登録。
  - `markAppAuthSignedIn()` – `authMethod = "appauth"` と
    `accountEmail = "appauth_signed_in"`（未設定時）を保存し、
    `AppAuthAuth.get(app)` をレジストリに登録。
- `UploadSettingsViewModel` は `DrivePrefs.accountEmail` が非空のときだけ
  「Drive 設定済み」とみなし、Upload トグルを ON にできるようにします。

---

## UI / Compose 概要

- `MainActivity`
  - `"home"`, `"drive_settings"`, `"upload_settings"` を持つ `NavHost` をホスト。
  - 権限をリクエストし、許可後に `GeoLocationService` を起動。

- `AppRoot`
  - `"home"`, `"pickup"`, `"map"` を持つ `NavHost` を内部に持つ。
  - AppBar:
    - ルートに応じて GeoLocation / Pickup / Map のタイトルを表示。
    - Pickup / Map では戻るボタン、Home では `Map` / `Pickup` / `Drive` / `Upload`
      ボタンと `ServiceToggleAction` を表示。

- `GeoLocationProviderScreen`
  - Interval / DR interval 設定（`IntervalSettingsViewModel`）と履歴リスト
    (`HistoryViewModel`) を表示。
  - `HistoryViewModel` は `StorageService.latestFlow(limit = 9)` を入力とする
    メモリ上のバッファを持ち、`timeMillis` 降順で最大 9 件の `LocationSample` を保持します。
    RealtimeUploadManager や MidnightExportWorker が Room 側の行を削除しても、
    バッファから自然に溢れるまでは履歴リストから急に消えません。

- `PickupScreen`
  - `SelectorUseCases` を通じて Pickup 条件と結果を扱います。

- `MapScreen` / `GoogleMapsExample`
  - MapConductor `GoogleMapsView` を用いて GPS/DR のポリラインと
    デバッグオーバーレイ（件数、静止フラグ、DR–GPS 距離など）を描画します。

- `DriveSettingsScreen`
  - 認証方式（Credential Manager / AppAuth）の切り替え、
    サインイン / サインアウト、Drive フォルダ設定、
    「Backup days before today」「Today preview」アクションを提供します。

- `UploadSettingsScreen`
  - Upload on/off、`UploadSchedule`（NONE/NIGHTLY/REALTIME）、
    `intervalSec`（秒）、`zoneId`（IANA タイムゾーン）を編集し、
    Drive 未設定時には警告を表示します。

---

## 公開ライブラリ API（概要）

ライブラリとして利用する場合の主な入口は次のとおりです。

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

---

## 開発メモ

- コードは Kotlin + Jetpack Compose + Room + DataStore + WorkManager を中心に構成されています。
- プロダクションコード（Kotlin / Java / XML / Gradle）は ASCII のみを使用し、
  日本語などの多言語テキストは `README_JA.md` / `AGENTS_JA.md` などの
  Markdown ドキュメントにのみ記載します。
- Drive 認証や Drive 連携の挙動を変更した場合は、README（EN/JA/ES）に記載した
  OAuth スコープと redirect URI が Google Cloud Console の設定と一致していることを
  確認してください。

