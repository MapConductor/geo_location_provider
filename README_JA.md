# GeoLocationProvider (日本語版)

GeoLocationProvider は、Android 上で **位置情報を記録・保存・エクスポート・アップロード** するための SDK ＆サンプルアプリケーションです。  
バックグラウンドで位置情報を Room データベースに保存し、GeoJSON+ZIP 形式でエクスポートし、必要に応じて **Google Drive にアップロード** できます。

---

## 機能概要

- バックグラウンドでの位置情報取得（サンプリング間隔は設定可能）
- Room データベースへの保存（`LocationSample`, `ExportedDay`）
- GeoJSON 形式へのエクスポート（ZIP 圧縮対応）
- `MidnightExportWorker` による日次エクスポート（前日までのバックログ処理）
- 昨日のバックアップ / 今日のプレビュー用の手動エクスポート
- Pickup 機能（期間・件数などの条件に基づく代表サンプル抽出）
- Google Drive へのアップロード（フォルダ選択・エラー記録付き）

---

## アーキテクチャ

### モジュール構成

このリポジトリはマルチモジュール構成の Gradle プロジェクトです。

- `:app` – Jetpack Compose ベースのサンプル UI（履歴一覧、Pickup 画面、Drive 設定画面、手動エクスポートなど）
- `:core` – 位置取得サービス (`GeoLocationService`)、センサー処理、サンプリング間隔の適用。永続化は `:storageservice` に委譲し、DR は `:deadreckoning` を利用。
- `:storageservice` – Room `AppDatabase` / DAO と `StorageService` ファサード。位置ログとエクスポート状態の唯一の入口。
- `:dataselector` – Pickup 用の選択ロジック。`LocationSample` を条件でフィルタし、代表行 (`SelectedSlot`) を構築。
- `:datamanager` – GeoJSON エクスポート、ZIP 圧縮、`MidnightExportWorker` / `MidnightExportScheduler`、Google Drive 連携。
- `:deadreckoning` – Dead Reckoning エンジンと公開 API (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`)。
- `:auth-appauth` – AppAuth ベースの `GoogleDriveTokenProvider` 実装。
- `:auth-credentialmanager` – Credential Manager + Identity ベースの `GoogleDriveTokenProvider` 実装。

依存関係の向き（概要）は次の通りです。

- `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, 認証系モジュール  
- `:core` → `:storageservice`, `:deadreckoning`  
- `:datamanager` → `:storageservice`, Drive 連携クラス  
- `:dataselector` → `LocationSampleSource` 抽象のみ（実装は `:app` 側で `StorageService` をラップ）

### 主なコンポーネント

- **エンティティ: `LocationSample` / `ExportedDay`**  
  `LocationSample` は緯度・経度・時刻・速度・バッテリー残量などを保持します。  
  `ExportedDay` は日ごとのエクスポート状態（ローカル出力済みか、アップロード結果、最終エラーなど）を管理します。

- **`StorageService`**  
  Room (`AppDatabase` / DAO) への唯一の入口となるファサードです。  
  他モジュールは DAO を直接触らず、`StorageService` 経由で DB にアクセスします。  
  - `latestFlow` – 最新 N 件の `LocationSample` を新しい順の Flow で提供  
  - `getLocationsBetween` – `[from, to)` の半開区間で位置ログを取得（Pickup やエクスポートで利用）

- **Pickup (`:dataselector`)**  
  `SelectorCondition`, `SelectorRepository`, `BuildSelectedSlots`, `SelectorPrefs` が Pickup 機能を構成します。  
  ログを間引き・グリッド吸着して代表サンプルを構築し、欠測は `SelectedSlot(sample = null)` で表現します。  
  `LocationSampleSource` がサンプル供給元を抽象化し、サンプルアプリでは `StorageService` を使った実装を提供します。

- **Dead Reckoning (`:deadreckoning`)**  
  公開 API (`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`) と internal なエンジン実装 (`DeadReckoningEngine`, `DeadReckoningImpl` など) から構成されます。  
  `GeoLocationService` は `DeadReckoningFactory.create(applicationContext)` でインスタンスを生成し、`start()` / `stop()` で制御します。

- **日次エクスポート (`MidnightExportWorker` / `MidnightExportScheduler`)**  
  WorkManager ベースのパイプラインで、次の処理を行います。
  - タイムゾーン `Asia/Tokyo` で「前日まで」の日付を走査
  - 各日について `StorageService.getLocationsBetween` で `LocationSample` を取得
  - `GeoJsonExporter.exportToDownloads` で GeoJSON+ZIP に変換
  - `markExportedLocal` によるローカル出力済みフラグの設定
  - `UploaderFactory` + `GoogleDriveTokenProvider` による Drive アップロード
  - `markUploaded` / `markExportError` による結果の記録
  - ZIP ファイルの削除、およびアップロード成功時の古いレコード削除

- **Drive 連携 (`GoogleDriveTokenProvider` など)**  
  `GoogleDriveTokenProvider` は Drive 用アクセストークンを抽象化します。  
  `DriveTokenProviderRegistry` はバックグラウンド用のプロバイダ登録 / 取得を担い、`Uploader` / `UploaderFactory`（`KotlinDriveUploader` が内部実装）と組み合わせてアップロードを行います。  
  `GoogleAuthRepository`（GoogleAuthUtil ベース）は後方互換のために残されていますが、新規コードでは非推奨です。

---

## セットアップ

### 0. 前提

- Android Studio（AGP 8.1 以上相当）
- JDK 17（Gradle / Kotlin のターゲット）
- Android SDK API 26 以上（プロジェクトでは `compileSdk = 36` を使用）

### 1. `local.properties` の用意

`local.properties` は開発マシン固有の設定（Android SDK のパスなど）を保持します。  
通常は Android Studio が自動生成しますが、無い場合はルートに作成してください。

```properties
sdk.dir=C:\\Android\\Sdk
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

### 2. `secrets.properties` の用意

`secrets.properties` は `secrets-gradle-plugin` 経由で利用される、認証関連の値などを保持します。  
リポジトリにはテンプレートとして `local.default.properties` が含まれています。

```bash
cp local.default.properties secrets.properties
```

- Credential Manager 用: `CREDENTIAL_MANAGER_SERVER_CLIENT_ID`（Web / サーバクライアント ID）
- AppAuth 用: `APPAUTH_CLIENT_ID`（インストールアプリ + カスタム URI スキーム）

実際に Drive 連携や Maps を使う場合は、Cloud Console でクライアント ID / API キーを発行し、`secrets.properties` に設定してください。

### 3. ビルド

```bash
./gradlew :app:assembleDebug
```

---

## 開発スタイル

- Kotlin のコーディング規約に準拠
- UI は Jetpack Compose ベース
- 永続化は Room + DataStore（設定用）で実装
- バックグラウンド処理は WorkManager（`MidnightExportWorker`, `MidnightExportScheduler`）で実装
- 表示フォーマットなどは `Formatters.kt` などのユーティリティに集約

### ソースコードのエンコーディングとコメント方針

- 本リポジトリの本番コード（Kotlin / Java / XML / Gradle スクリプトなど）は、**ASCII のみ**で記述されています。  
  ツールやプラットフォーム間でのエンコーディング問題を避けるため、コード・コメント・文字列リテラルにはマルチバイト文字を使用していません。
- 日本語・スペイン語を含む多言語ドキュメントは、`README_JA.md`, `README_ES.md` やその他の `*.md` ファイルとして別途提供されます。
- コメントスタイルはモジュール間で統一されています:
  - 公開 API や主要クラス: 役割・設計方針・利用方法・契約を説明する KDoc（`/** ... */`）。
  - 内部実装の補足: 簡潔な `// ...` 行コメントと、`// ---- Section ----` のようなシンプルなセクション見出し。
  - 装飾的な罫線やモジュールごとに異なるバナー形式は使用しません。

---

## 機能実装状況

| 機能                           | 状況       | 備考                                          |
|--------------------------------|------------|-----------------------------------------------|
| 位置ログ記録（Room）           | [v] 実装済 | Room DB に保存                                |
| 日次エクスポート（GeoJSON+ZIP）| [v] 実装済 | `MidnightExportWorker` により 0 時に自動実行  |
| Google Drive アップロード      | [v] 実装済 | Kotlin ベースの Uploader を利用               |
| Pickup（期間 / 件数ベース）    | [v] 実装済 | ViewModel + UseCase + Compose UI で構成       |
| Drive フルスコープ認証         | [-] 検討中 | 既存ファイルのフルブラウズ / 更新は未サポート |
| UI: DriveSettingsScreen        | [v] 実装済 | 認証・フォルダ設定・接続テスト                |
| UI: PickupScreen               | [v] 実装済 | 抽出条件入力と結果表示                        |
| UI: 履歴一覧                   | [v] 実装済 | 保存済みサンプルの時系列表示                  |

