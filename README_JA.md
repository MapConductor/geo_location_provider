# GeoLocationProvider (日本語版)

GeoLocationProvider は、Android 上で **位置情報を記録・保存・エクスポート・アップロード** するための SDK ＋サンプルアプリです。  
バックグラウンドで位置情報を Room データベースに保存し、GeoJSON+ZIP 形式でエクスポートし、必要に応じて **Google Drive にアップロード** できます。

---

## 機能概要

- バックグラウンドでの位置取得（サンプリング間隔は設定可能）
- Room データベースへの保存（`LocationSample`, `ExportedDay`）
- GeoJSON 形式へのエクスポート（ZIP 圧縮対応）
- `MidnightExportWorker` による日次エクスポート（前日までのバックログ処理）
- 昨日のバックアップ／今日のプレビュー用の手動エクスポート
- Pickup 機能（期間・件数などの条件に基づく代表サンプル抽出）
- Google Drive へのアップロード（フォルダ選択／エラー記録付き）

---

## アーキテクチャ

### モジュール構成

このリポジトリはマルチモジュール構成の Gradle プロジェクトです。

- `:app` – Jetpack Compose ベースのサンプル UI（履歴一覧、Pickup 画面、Drive 設定、手動エクスポート、Map 画面など）。
- `:core` – 位置取得サービス `GeoLocationService`、センサー処理、サンプリング間隔の適用。永続化は `:storageservice` に委譲し、DR は `:deadreckoning` を利用します。
- `:storageservice` – Room `AppDatabase` / DAO と `StorageService` ファサード。位置ログとエクスポート状態の唯一の入口。
- `:dataselector` – Pickup 用の選択ロジック。`LocationSample` を条件でフィルタし、代表行 `SelectedSlot` を構築します。
- `:datamanager` – GeoJSON エクスポート、ZIP 圧縮、`MidnightExportWorker` / `MidnightExportScheduler`、Google Drive 連携。
- `:deadreckoning` – Dead Reckoning エンジンと公開 API （`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`）。
- `:auth-appauth` / `:auth-credentialmanager` – `GoogleDriveTokenProvider` の実装を提供する認証モジュール。

依存関係（概要）は次の通りです。

- `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, 認証系モジュール  
- `:core` → `:storageservice`, `:deadreckoning`  
- `:datamanager` → `:storageservice`, Drive 連携クラス  
- `:dataselector` → `LocationSampleSource` 抽象のみ（実装は `:app` 側で `StorageService` をラップ）

### 主なコンポーネント

- **エンティティ: `LocationSample` / `ExportedDay`**  
  `LocationSample` は緯度・経度・時刻・速度・バッテリー残量などを保持します。  
  `ExportedDay` は日ごとのエクスポート状態（ローカル出力済みか、アップロード結果、最終エラーなど）を管理します。

- **`StorageService`**  
  Room（`AppDatabase` / DAO）への唯一の入口となるファサードです。  
  他モジュールは DAO を直接触らず、`StorageService` 経由で DB にアクセスします。

- **Pickup (`:dataselector`)**  
  `SelectorCondition`, `SelectorRepository`, `BuildSelectedSlots`, `SelectorPrefs` で Pickup 機能を構成します。  
  ログを間引き・グリッド吸着して代表サンプルを構築し、欠測は `SelectedSlot(sample = null)` で表現します。  
  `LocationSampleSource` がサンプルの供給元を抽象化し、サンプルアプリでは `StorageService` を使った実装を提供します。

- **Dead Reckoning (`:deadreckoning`)**  
  公開 API（`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`）と、internal なエンジン実装（`DeadReckoningEngine`, `DeadReckoningImpl` など）から構成されます。  
  `GeoLocationService` は `DeadReckoningFactory.create(applicationContext)` でインスタンスを生成し、`start()` / `stop()` で制御します。

  DR の挙動のポイントは次の通りです。

  - **GPS アンカー**
    - `submitGpsFix()` が呼ばれるたびに、内部の位置状態（`lat`, `lon`）を **最新 GPS の位置に再アンカー** します。
    - 速度は `velocityGain` に従って GPS 速度へ平滑に寄せます（例: 10% ずつ追従）。
    - 位置の不確かさ `sigma2Pos` は、GPS 精度（accuracy）から再初期化されます。
  - **物理速度ガード (`maxStepSpeedMps`)**
    - 各センサステップで移動距離とステップ速度を推定し、`stepSpeedMps > maxStepSpeedMps` の場合はそのステップを破棄します。
    - デフォルト値はおおよそ 340 m/s 程度で、航空機を含めた現実的な上限を想定しています。0 以下を指定すると無効化されます。
  - **静止検知 (`isLikelyStatic`)**
    - 加速度ノルム・ジャイロノルムの分散がしきい値以下のとき、短時間の「ほぼ静止」とみなします（ZUPT 風）。
    - 静止中は速度を漸減させることでランダムウォークを抑制します。
  - **予測 API (`predict`)**
    - `predict(fromMillis, toMillis)` は内部状態のスナップショットを返す API で、IMU 積分自体は `onSensor()` が担います。
    - **初回 GPS フィックス前** は絶対位置が未定のため、空リストを返す場合があります（呼び出し側は「まだ絶対位置がない」状態を許容する必要があります）。

- **日次エクスポート（`MidnightExportWorker` / `MidnightExportScheduler`）**  
  WorkManager ベースのパイプラインで、次の処理を行います。
  - タイムゾーン `Asia/Tokyo` で「前日まで」を走査
  - `StorageService.getLocationsBetween` で対象日の `LocationSample` を取得
  - `GeoJsonExporter.exportToDownloads` で GeoJSON+ZIP に変換
  - `markExportedLocal` によるローカル出力済みフラグの設定
  - `UploaderFactory` + `GoogleDriveTokenProvider` による Drive アップロード
  - `markUploaded` / `markExportError` による結果の記録
  - ZIP ファイルの削除、およびアップロード成功時の古いレコード削除

- **Drive 連携 (`GoogleDriveTokenProvider` など)**  
  `GoogleDriveTokenProvider` は Drive 用アクセストークンを抽象化します。  
  `DriveTokenProviderRegistry` はバックグラウンド用のプロバイダ登録／取得を行い、`Uploader` / `UploaderFactory`（内部で `KotlinDriveUploader` を利用）が実際のアップロードを行います。

- **UI (Compose) – `MainActivity`, `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen`**  
  アプリは 2 段階の `NavHost` 構成になっています。
  - Activity 直下の `NavHost`: `"home"`, `"drive_settings"`（AppBar の Drive メニューから Drive 設定画面へ遷移）
  - `AppRoot` 内の `NavHost`: `"home"`, `"pickup"`（AppBar の Pickup ボタンで Home / Pickup を切り替え）  
  権限は `ActivityResultContracts.RequestMultiplePermissions` でリクエストし、Start/Stop トグルは `ServiceToggleAction` にカプセル化されています。

  Map 画面（`GeoLocationProviderScreen` の `Map` タブ）は MapConductor + Google Maps backend を用いて次のように動作します。

  - `GPS` / `DeadReckoning` のチェックボックスと `Count (1-1000)` フィールド、`Apply` / `Cancel` ボタンで、
    - どのプロバイダを表示するか
    - GPS + DR の合計で何件まで表示するか（DB に 100 件未満しかない場合は全件）
    を制御します。
  - 表示は **マーカーではなくポリライン** です。
    - DeadReckoning: 赤色・細めのポリライン、GPS より前面（上側）に描画。
    - GPS: 青色・太めのポリライン、DeadReckoning より背面（下側）に描画。
    - 点の接続は距離順ではなく、`timeMillis` による **時系列順** です。
  - `Apply` 押下でフィルタ条件を確定し、チェックボックスと Count をロック、ボタンラベルを `Cancel` に変更します。
  - `Cancel` 押下でポリラインをクリアし、チェックボックスと Count をアンロック、ボタンラベルを `Apply` に戻します。  
    このとき、マップのカメラ位置／ズームは変えません（ユーザーが調整した視点を維持）。
  - 画面右上には半透明のデバッグオーバーレイを表示し、
    - `GPS : 表示件数 / DB 件数`
    - `DR  : 表示件数 / DB 件数`
    - `ALL : 表示総数 / DB 総数`
    を表示して簡易的な整合性チェックが行えるようにしています。

---

## 公開 API の概要

ライブラリとして利用する場合、通常は次のモジュールと型を参照します。

- `:core`
  - `GeoLocationService` – 位置ログを DB に記録する Foreground Service。
  - `UploadEngine` – 利用するアップロードエンジンを表す enum。
- `:storageservice`
  - `StorageService` – Room (`LocationSample`, `ExportedDay`) への Facade。
  - `LocationSample`, `ExportedDay` – 位置ログとエクスポート状態の主要エンティティ。
  - `SettingsRepository` – サンプリング間隔／DR 間隔の設定。
- `:dataselector`
  - `SelectorCondition`, `SelectedSlot`, `SortOrder` – Pickup／検索のドメインモデル。
  - `LocationSampleSource` – `LocationSample` 供給源の抽象。
  - `SelectorRepository`, `BuildSelectedSlots` – コア選択ロジックと小さなユースケースラッパ。
  - `SelectorPrefs` – Pickup 条件を保存する DataStore ラッパ。
- `:datamanager`
  - `GeoJsonExporter` – `LocationSample` を GeoJSON + ZIP にエクスポート。
  - `GoogleDriveTokenProvider` – Drive アクセストークンの抽象。
  - `DriveTokenProviderRegistry` – バックグラウンド用トークンプロバイダの登録。
  - `Uploader`, `UploaderFactory` – Drive アップロードのエントリポイント。
  - `DriveApiClient`, `DriveFolderId`, `UploadResult` – Drive REST 用ヘルパ／結果型。
  - `MidnightExportWorker`, `MidnightExportScheduler` – 日次エクスポートのパイプライン。
- `:deadreckoning`
  - `DeadReckoning`, `GpsFix`, `PredictedPoint` – DR エンジン API。
  - `DeadReckoningConfig`, `DeadReckoningFactory` – 設定オブジェクトとファクトリ。
- `:auth-credentialmanager` / `:auth-appauth`（任意）
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider` – `GoogleDriveTokenProvider` の参照実装。

その他の型（DAO や `AppDatabase` 定義、リポジトリ実装、低レベル HTTP ヘルパなど）は **内部実装** とみなし、互換性を保証しません。

---

## 補足

- 本ファイルはドキュメントのため日本語を含みますが、コード・コメント・文字列リテラルは ASCII のみとしてください（`AGENTS.md` のポリシーに従うこと）。
- Dead Reckoning や Map 表示仕様を変更した場合は、英語版 `README.md` とあわせて本ファイルも更新してください。

