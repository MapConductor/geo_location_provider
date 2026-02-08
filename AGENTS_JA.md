# Repository Guidelines (日本語版)

このファイルは、GeoLocationProvider リポジトリのガイドラインを日本語でまとめたものです。
正本(最新版)は英語版の `AGENTS.md` です。

モジュール境界をまたぐ挙動を変更する場合は、まず `AGENTS.md` を更新し、その後に該当モジュールの README を更新してください。

---

## ドキュメントの地図

- ルート概要:
  - `README.md` (EN)
  - `README_JA.md` (JA)
  - `README_ES.md` (ES)
- モジュール別 README (モジュール境界の使い方はここが主):
  - 各モジュール直下に以下があります:
    - `README.md` (EN)
    - `README_JP.md` (JP)
    - `README_ES.md` (ES)

---

## プロジェクト構成とモジュール

ルート Gradle プロジェクト: `GeoLocationProvider`

モジュール:

- `:app`: Jetpack Compose のサンプルアプリ(履歴、Pickup、Map、Drive 設定、Upload 設定など)。
- `:core`: フォアグラウンドサービス `GeoLocationService` と実行時オーケストレーション(GPS、任意の補正、任意の Dead Reckoning、永続化)。
- `:gps`: GPS 抽象(`GpsLocationEngine`, `GpsObservation`)と、現状のデフォルト実装(`LocationManagerGpsEngine`)。
- `:storageservice`: Room(DB/DAO は内部)と `StorageService` facade。サンプリング/DR 設定の `SettingsRepository` (DataStore)も含む。
- `:dataselector`: `LocationSampleSource` と `SelectorCondition` による選択ロジック(Room 依存なし)。
- `:datamanager`: エクスポート(GeoJSON/GPX、任意で ZIP)と Google Drive へのアップロード、バックグラウンドスケジューリング。
- `:deadreckoning`: IMU ベースの Dead Reckoning エンジンと公開 API。
- `:auth-appauth`: AppAuth ベースの `GoogleDriveTokenProvider`。
- `:auth-credentialmanager`: Credential Manager + Identity ベースの `GoogleDriveTokenProvider`。
- `mapconductor-core-src`: `:app` のみから利用する MapConductor の vendored ソース(基本は読み取り専用)。

依存方向(概略):

- `:app` -> `:core`, `:dataselector`, `:datamanager`, `:storageservice`, `:deadreckoning`, `:gps`, auth モジュール
- `:core` -> `:gps`, `:storageservice`, `:deadreckoning`
- `:datamanager` -> `:core`, `:storageservice`
- `:dataselector` -> `LocationSampleSource` 抽象(および共有モデル `LocationSample`)のみ(実装は `:app` 側で `StorageService` に委譲)

ローカル専用(コミットしない):

- `local.properties`: SDK パス等のマシン依存設定
- `secrets.properties`: Secrets Gradle Plugin の値(`local.default.properties` をテンプレートとして利用)

---

## ビルド/テスト

リポジトリ直下で実行:

- ビルド: `./gradlew :app:assembleDebug`
- インストール: `./gradlew :app:installDebug`
- Lint: `./gradlew lint`
- Unit テスト: `./gradlew test`
- Android テスト: `./gradlew :app:connectedAndroidTest`

---

## コーディング規約とエンコード

- インデントは 4 スペース。
- 未使用 import と不要コードは見つけたら削除。

ASCII-only ルール(本番コード):

- 本番ソース(Kotlin/Java/XML/Gradle)は ASCII のみ。
- 多言語(JA/ES)はドキュメント(`*.md`)のみ許可。

---

## レイヤ責務とモジュール境界

### StorageService / Room (`:storageservice`)

ルール:

- Room DAO を直接触るのは `:storageservice` のみ。
- 他モジュールは `StorageService` を利用する。

重要な契約:

- `latestFlow(ctx, limit)`: newest-first (`timeMillis` 降順)
- `getLocationsBetween(ctx, from, to, softLimit)`: 半開区間 `[from, to)`、`timeMillis` 昇順
- `deleteLocations(ctx, items)`: 空リストは no-op

### dataselector / Pickup (`:dataselector`)

- 依存は `LocationSampleSource` 抽象(および共有モデル `LocationSample`)のみ。
- Room/DAO や `StorageService` を知らないこと。
- 範囲は `[from, to)`。
- `intervalSec == null`: 直抽出(グリッドなし)。
- `intervalSec != null`: グリッドスナップ、欠損は `SelectedSlot(sample = null)`。

### GeoLocationService / GPS / DR (`:core`, `:gps`, `:deadreckoning`)

`GeoLocationService` は以下をオーケストレーションします:

- GPS 観測: `GpsLocationEngine` (現状は `LocationManagerGpsEngine`)。
- (任意) 補正: `GpsCorrectionEngineRegistry`。
- (任意) Dead Reckoning: `:deadreckoning`。
- 永続化: `StorageService`。

`LocationSample.provider` の文字列:

- `"gps"`: raw GPS
- `"gps_corrected"`: 補正(有効かつ raw と差が出た場合のみ)
- `"dead_reckoning"`: DR サンプル

DR モード:

- `DrMode.Prediction`: ticker で realtime の `dead_reckoning` を追加挿入。
- `DrMode.Completion`: GPS fix 間を backfill して `dead_reckoning` を挿入(リアルタイム ticker は使わない)。

### SettingsRepository (DataStore)

- `intervalSecFlow`: GPS 間隔(秒)
- `drIntervalSecFlow`: DR 間隔(秒)
  - `0`: DR 無効(GPS のみ)
  - `> 0`: 最低 1 秒にクランプ
- `drGpsIntervalSecFlow`: GPS->DR submit の間引き(0 は毎回)
- `drModeFlow`: Prediction / Completion

サービスはこれらの Flow を購読し、稼働中に挙動を更新します(再起動不要)。

---

## 認証と Drive 連携

`GoogleDriveTokenProvider` の契約:

- 戻り値は bare token("Bearer " なし)。
- 通常の失敗は例外ではなく `null` で表現。
- バックグラウンドから UI を開始しない。UI が必要なら `null` を返し、フォアグラウンドでサインイン/認可を行う。

背景登録:

- `DriveTokenProviderRegistry` にプロセス内の background provider を登録し、`:datamanager` が利用します。

---

## エクスポート/アップロード (`:datamanager`)

Nightly(バックログは基本的に昨日まで):

- `MidnightExportScheduler` / `MidnightExportWorker`
- タイムゾーン: `UploadPrefsRepository.zoneId` (デフォルト `Asia/Tokyo`)
- 日別範囲: `[00:00, 24:00)` (半開区間)
- 各日について:
  - `StorageService.getLocationsBetween(...)` で取得
  - Downloads/`GeoLocationProvider` にエクスポート
  - Drive にアップロード(設定されている場合)
  - 試行後に一時ファイルを削除
  - 成功時のみその日の DB 行を削除

Realtime(スナップショット + drain):

- `RealtimeUploadManager`
- schedule が `REALTIME` のとき `StorageService.latestFlow(limit = 1)` を監視
- 成功時にアップロード済み DB 行を削除(upload-as-drain)

解決ルール:

- Drive folder id: `DrivePrefsRepository` を優先し、`AppPrefs`(legacy)にフォールバック
- Upload engine: `AppPrefs.engine == UploadEngine.KOTLIN` のときのみアップロード実行

---

## UI / Compose (`:app`)

サンプルアプリは参照実装(ホストアプリの例)です:

- `GeoLocationService` を start/stop。
- `SettingsRepository` に設定を書き込み。
- `StorageService.latestFlow(...)` から履歴を表示。
- 認証フローを実行し、background provider を登録。
- `DrivePrefsRepository` / `UploadPrefsRepository` で export/upload を設定。

Map 画面の provider 表示:

- GPS: `provider="gps"`
- GPS(EKF): `provider="gps_corrected"`
- Dead Reckoning: `provider="dead_reckoning"`

---

## セキュリティと OAuth メモ

コミット禁止:

- `local.properties`, `secrets.properties`, `google-services.json` など

クライアント ID は分ける:

- Credential Manager: `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` (web/server)
- AppAuth: `APPAUTH_CLIENT_ID` (installed app + custom scheme redirect)

---

## 公開 API(安定面)

安定させたい型(それ以外は可能な限り `internal`):

- `:storageservice`: `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`: `SelectorCondition`, `SortOrder`, `SelectedSlot`, `LocationSampleSource`, `SelectorRepository`
- `:datamanager`: `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`, `DrivePrefsRepository`, `UploadPrefsRepository`, `UploadSchedule`, `Uploader`, `UploaderFactory`, `GeoJsonExporter`, `RealtimeUploadManager`, `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`: `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`
- `:gps`: `GpsLocationEngine`, `GpsObservation`
