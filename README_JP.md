# GeoLocationProvider

GeoLocationProvider は、Android 上で **位置情報を記録・保存・エクスポート** するための SDK & サンプルアプリケーションです。  
さらに、エクスポートしたデータを **Google Drive にアップロード** する機能も提供します。

---

## Features (機能)

- バックグラウンドでの位置情報取得 (サンプリング間隔は設定可能)
- Room データベースによる保存
- GeoJSON 形式へのエクスポート (ZIP 圧縮付き)
- 毎日 0時に自動実行するエクスポート (Midnight Export Worker)
- 昨日分バックアップ・今日分プレビューの手動エクスポート
- Pickup 機能 (期間または件数を指定して代表サンプルを抽出)
- Google Drive アップロード機能 (フォルダ指定可能)

---

## Architecture (アーキテクチャ)
### Module Structure (モジュール構成)

> 本リポジトリには app, core, dataselector, datamanager の各モジュールが同梱されています。  
> 利用者が追加でモジュール登録や依存関係の設定を行う必要はありません。

- **:core**  
  位置情報の取得、Room エンティティ/DAO/データベース処理、ユーティリティを提供

- **:dataselector**  
  Pickup など条件に基づいたデータ抽出ロジックを提供

- **:datamanager**  
  データのエクスポート、Google Drive 連携、WorkManager タスクを提供

- **:app**  
  Compose ベースの UI (履歴リスト、Pickup 画面、Drive 設定画面など)

### Key Components (主要コンポーネント)

- **LocationSample / ExportedDay エンティティ**  
  アプリで収集した位置情報を保持する基本データモデル。`LocationSample` は緯度経度や時刻、バッテリー残量などの情報を保持し、`ExportedDay` は「既にエクスポート済みの日付」を記録して二重出力を防止します。

- **PickupViewModel & BuildSelectRows ユースケース**  
  データを間引いて抽出する Pickup 機能の中核。指定した間隔や件数に基づいて代表的なサンプルを選び出し、UI に流すための処理をまとめています。

- **MidnightExportWorker & MidnightExportScheduler**  
  WorkManager を用いたバックグラウンドタスク。毎日0時に前日分のデータを GeoJSON + ZIP にエクスポートし、Google Drive アップロードまでを自動で実行します。スケジューラは次回の実行予約を担います。

- **GoogleAuthRepository & DriveApiClient**  
  Google Drive 連携のための基盤。`GoogleAuthRepository` は OAuth 認証やトークン管理を行い、`DriveApiClient` は Drive API を呼び出してファイルアップロードやフォルダ検証を実行します。

- **UI 画面群 (MainActivity, PickupScreen, DriveSettingsScreen など)**  
  Jetpack Compose で実装されたユーザーインターフェース。
    - `MainActivity`: サービスの起動/停止や履歴表示のエントリポイント
    - `PickupScreen`: 条件を指定してサンプルを抽出しリスト表示
    - `DriveSettingsScreen`: Google Drive 認証・フォルダ設定・接続テスト

---

## Quick Start (クイックスタート)

> app, core, dataselector, datamanager 配下のファイルはリポジトリに含まれており、セットアップ対象外です。  
> 以下では、ローカル専用ファイルの作成と Google Drive 連携準備のみを説明します。

### 1. local.default.properties / local.properties の準備

`local.properties` は **各開発者マシン固有** の設定を置くためのファイルです (通常は Git 管理対象外)。  
本プロジェクトでは、初期値のテンプレートとして `local.default.properties` を提供する想定です。

- 推奨フロー:
    1) ルートに `local.default.properties` を用意 (テンプレート)
    2) 初回セットアップ時に `local.properties` を手動作成または複製
    3) 必要に応じて値を編集

**例: local.default.properties (テンプレート。コミット可)**
```properties
# Android SDK の場所。空またはダミー値で構いません
sdk.dir=/path/to/Android/sdk

# 任意: Gradle JVM オプション
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
```

**例: local.properties (各自の実環境。コミットしない)**
```properties
sdk.dir=C:\Android\Sdk          # Windows の例
# sdk.dir=/Users/you/Library/Android/sdk   # macOS の例
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

> メモ: `local.properties` は Android Studio が自動生成する場合もあります。生成済みなら編集のみでOKです。

### 2. secrets.properties の準備

`secrets.properties` は **認証や機密性のあるデフォルト値** を置くためのローカルファイルです (Git 管理対象外)。  
アプリの UI から設定できる項目もありますが、CI や開発効率のために既定値を置きたい場合に使用します。

**例: secrets.properties (コミットしない)**
```properties
# 任意: アップロード先のデフォルトフォルダID (UIで上書き可)
DRIVE_DEFAULT_FOLDER_ID=

# 任意: アップロードエンジンの既定値 (kotlin 以外を使う場合があれば指定)
UPLOAD_ENGINE=kotlin

# 任意: Drive のフルスコープ仕様を有効化する予定がある場合のフラグ
# (現時点ではフルスコープ(drive)での既存ファイル参照・更新は未実装のため効果はありません)
DRIVE_FULL_SCOPE=false
```

> 注意: 実装上これらのキーが無くてもアプリは動作します。必要な場合のみ追加してください。

### 3. Google Drive 連携の準備

1) Google Cloud Console でプロジェクトを作成
2) OAuth 同意画面を公開ステータスまで設定
3) 認証情報で **Android** の OAuth クライアントID を作成
4) `app/` 配下に `google-services.json` を配置 (コミットしない)
5) スコープは次を使用:
    - `https://www.googleapis.com/auth/drive.file` (アプリが作成/開いたファイルにアクセス)
    - `https://www.googleapis.com/auth/drive.metadata.readonly` (フォルダID検証など)

> `google-services.json` は各開発者/環境で異なるため、**必ずローカル配置**にしてください。

### 4. 権限の追加 (確認用)

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

### 5. ビルド

```bash
./gradlew assembleDebug
```

---

## Development (開発者向け)

- Kotlin コーディング規約準拠
- UI は Jetpack Compose
- データ永続化は Room
- バックグラウンド処理は WorkManager
- 表示の共通化は `Formatters.kt` を利用

---

## Feature Implementation Status (実装状況)

| 機能                           | 状況         | 備考                                                  |
|--------------------------------|--------------|-------------------------------------------------------|
| 位置情報記録 (Room)            | [v] 実装済み | Room DB に保存                                        |
| 日次エクスポート (GeoJSON+ZIP) | [v] 実装済み | MidnightExportWorker により0時に実行                  |
| Google Drive アップロード      | [v] 実装済み | Kotlin 実装のアップローダーを使用                     |
| Pickup (間隔/件数指定)         | [v] 実装済み | ViewModel + UseCase によりUIと連動                    |
| Drive フルスコープ認証         | [-] 対応中   | フルスコープ(drive)での既存ファイル参照・更新は未実装 |
| UI: DriveSettingsScreen        | [v] 実装済み | 認証・フォルダ設定・テスト機能あり                    |
| UI: PickupScreen               | [v] 実装済み | 抽出条件入力・結果リスト表示                          |
| UI: 履歴リスト                 | [v] 実装済み | 保存済みサンプルの時系列表示                          |

---
