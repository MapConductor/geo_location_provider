# GeoLocationProvider

GeoLocationProvider は、Android 上で **位置情報を記録・保存・エクスポート** するための SDK & サンプルアプリケーションです。  
エクスポートしたデータを **Google Drive にアップロード** する機能も提供します。

---

## 機能 (Features)

- バックグラウンドでの位置情報取得（サンプリング間隔は設定可能）
- Room データベースによる永続化
- GeoJSON 形式へのエクスポート（ZIP 圧縮付き）
- 毎日 0 時に自動実行するエクスポート（Midnight Export Worker）
- 昨日分バックアップ／今日分プレビューの手動エクスポート
- Pickup 機能（期間・件数・精度などの条件に基づき代表サンプルを抽出）
- Google Drive アップロード機能（フォルダ指定可能）

---

## アーキテクチャ (Architecture)

### モジュール構成 (Module Structure)

> 本リポジトリには `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning`, `auth-*` のモジュールが含まれています。  
> サンプルアプリの構成をそのまま利用することも、必要なモジュールだけを取り出して利用することもできます。

- **:core**  
  位置情報取得（`GeoLocationService`）、センサー周辺ロジック、設定値の反映などを担当。

- **:storageservice**  
  Room `AppDatabase`・DAO と、それらへの唯一の入口となる `StorageService` を提供。位置ログ (`LocationSample`) とエクスポート状態 (`ExportedDay`) を一元管理します。

- **:dataselector**  
  Pickup などの条件に基づくデータ抽出ロジック。`SelectorCondition`・`SelectedSlot`・`SelectorRepository` などを提供します。

- **:datamanager**  
  GeoJSON へのエクスポート、ZIP 圧縮、`MidnightExportWorker`、Google Drive 連携 (`GoogleDriveTokenProvider`、`Uploader` など) を担当。

- **:deadreckoning**  
  Dead Reckoning エンジンと API (`DeadReckoning`, `GpsFix`, `PredictedPoint`) を提供。`GeoLocationService` から利用されます。

- **:auth-appauth / :auth-credentialmanager**  
  `GoogleDriveTokenProvider` の参考実装モジュール。AppAuth / Android Credential Manager を使ったトークン取得実装が含まれます。

- **:app**  
  Jetpack Compose ベースのサンプル UI（履歴リスト、Pickup 画面、Drive 設定画面など）。

### 主要コンポーネント (Key Components)

- **LocationSample / ExportedDay エンティティ**  
  `LocationSample` は緯度・経度・時刻・速度・バッテリー残量などを保持する位置ログ。  
  `ExportedDay` は「どの日付がエクスポート済みか」「Drive アップロードは成功したか」「エラー内容」などを管理します。

- **StorageService**  
  Room (`AppDatabase` / DAO) への「唯一の入り口」となるファサード。  
  他モジュールは `StorageService` 経由でのみ DB にアクセスし、DAO や `AppDatabase` を直接 import しない方針です。

- **Pickup / dataselector**  
  `SelectorCondition` と `SelectorRepository`, `BuildSelectedSlots` が Pickup ロジックの中核です。  
  条件に従ってログを間引き・グリッド吸着し、欠測を `SelectedSlot(sample=null)` で表現します。

- **MidnightExportWorker & MidnightExportScheduler**  
  WorkManager を用いたバックグラウンドタスク。毎日 0 時に前日分のログを GeoJSON + ZIP にエクスポートし、Google Drive へのアップロードと `ExportedDay` 状態の更新までを自動実行します。

- **GoogleDriveTokenProvider / Uploader / DriveApiClient**  
  Drive 連携の中核コンポーネント。  
  `GoogleDriveTokenProvider` はアクセストークン取得の抽象化、`Uploader` / `UploaderFactory` は Drive へのアップロード処理、`DriveApiClient` は `/files`, `/about` などの REST 呼び出しを扱います。

---

## Public API / 公式 API

GeoLocationProvider は「ライブラリ + サンプルアプリ」という構成です。  
他のアプリから再利用する際は、主に次のモジュール・クラスを「公式 API」として利用する想定です。

### モジュールと主なエントリポイント

- **`:core`**
  - `GeoLocationService` … 位置情報を記録する Foreground Service。`LocationSample` を DB に書き込みます。
  - `UploadEngine` … エクスポート／アップロード方式の種別（enum）。
- **`:storageservice`**
  - `StorageService` … Room (`LocationSample`, `ExportedDay`) への唯一の入口となるファサード。
  - `LocationSample`, `ExportedDay` … 位置ログとエクスポート状態を表すエンティティ。
  - `SettingsRepository` … サンプリング間隔・DR 間隔の設定値を管理。
- **`:dataselector`**
  - `SelectorCondition`, `SelectedSlot`, `SortOrder` … Pickup／抽出条件と結果のドメインモデル。
  - `LocationSampleSource` … `LocationSample` を供給するための抽象化インターフェース。
  - `SelectorRepository`, `BuildSelectedSlots` … 抽出ロジック本体と、画面用ユースケース。
  - `SelectorPrefs` … Pickup 条件を DataStore に保存／復元するためのファサード。
- **`:datamanager`**
  - `GeoJsonExporter` … `LocationSample` を GeoJSON + ZIP にエクスポート。
  - `GoogleDriveTokenProvider` … Google Drive アクセストークン取得の抽象インターフェース。
  - `DriveTokenProviderRegistry` … バックグラウンド用トークンプロバイダのレジストリ。
  - `Uploader`, `UploaderFactory` … Drive へのアップロードを行う高レベル API。
  - `DriveApiClient`, `DriveFolderId`, `UploadResult` … Drive REST 呼び出しと結果表現。
  - `MidnightExportWorker`, `MidnightExportScheduler` … 日次エクスポートの Worker とスケジューラ。
- **`:deadreckoning`**
  - `DeadReckoning`, `GpsFix`, `PredictedPoint` … DR エンジンの公開インターフェース。`GeoLocationService` から利用されます。
- **`:auth-credentialmanager` / `:auth-appauth`（任意）**
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider` … `GoogleDriveTokenProvider` の参考実装。

DAO や RoomDatabase、`*Repository` 実装、低レベルな HTTP ヘルパーなどはライブラリ内部の詳細であり、将来的に変更される可能性があります。

### 最小構成の統合例（ダイジェスト）

#### 1. 位置記録サービスを起動し、履歴を監視する

```kotlin
// Activity からサービスを起動／停止
private fun startLocationService() {
    val intent = Intent(this, GeoLocationService::class.java)
        .setAction(GeoLocationService.ACTION_START)
    ContextCompat.startForegroundService(this, intent)
}

private fun stopLocationService() {
    val intent = Intent(this, GeoLocationService::class.java)
        .setAction(GeoLocationService.ACTION_STOP)
    startService(intent)
}
```

```kotlin
// ViewModel から最新 N 件の履歴を Flow 監視
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    val latest: StateFlow<List<LocationSample>> =
        StorageService.latestFlow(app.applicationContext, limit = 100)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
```

#### 2. 保存済みログに対して Pickup を実行する

```kotlin
// StorageService を背後に持つ LocationSampleSource 実装
class StorageSampleSource(
    private val context: Context
) : LocationSampleSource {
    override suspend fun findBetween(fromInclusive: Long, toExclusive: Long): List<LocationSample> {
        return StorageService.getLocationsBetween(context, fromInclusive, toExclusive)
    }
}

suspend fun runPickup(context: Context): List<SelectedSlot> {
    val source = StorageSampleSource(context.applicationContext)
    val repo = SelectorRepository(source)
    val useCase = BuildSelectedSlots(repo)

    val cond = SelectorCondition(
        fromMillis = /* 開始時刻 (ms) */,
        toMillis = /* 終了時刻 (ms) */,
        intervalSec = 60,          // 60 秒グリッド
        limit = 1000,
        order = SortOrder.OldestFirst
    )
    return useCase(cond)
}
```

#### 3. Drive アップロードと日次エクスポートを設定する

```kotlin
// Application でトークンプロバイダ登録とスケジューラ設定
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 例: :auth-credentialmanager の CredentialManagerTokenProvider を利用
        val provider = CredentialManagerTokenProvider(
            context = this,
            serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
        )

        // MidnightExportWorker から利用されるバックグラウンド用プロバイダを登録
        DriveTokenProviderRegistry.registerBackgroundProvider(provider)

        // 日次エクスポートをスケジュール（毎日 0 時）
        MidnightExportScheduler.scheduleNext(this)
    }
}
```

```kotlin
// 任意のタイミングでファイルを Drive にアップロードする例（オプション）
suspend fun uploadFileNow(
    context: Context,
    tokenProvider: GoogleDriveTokenProvider,
    fileUri: Uri,
    folderId: String
): UploadResult {
    val uploader = UploaderFactory.create(
        context = context.applicationContext,
        engine = UploadEngine.KOTLIN,
        tokenProvider = tokenProvider
    ) ?: error("Upload engine not available")

    return uploader.upload(fileUri, folderId, fileName = null)
}
```

より詳細な説明やフルコードは、英語版の README（`README.md`）も併せて参照してください。

---

## クイックスタート (Quick Start)

> `app`, `core`, `dataselector`, `datamanager`, `storageservice` のソースコードはすでにリポジトリに含まれています。  
> ここではローカル専用ファイルの準備と Google Drive 連携の前提条件のみを説明します。

### 1. `local.default.properties` / `local.properties` の準備

`local.properties` は **開発者マシン固有** の設定を置くファイルです（通常は Git 管理外）。  
本プロジェクトでは、デフォルト値のテンプレートとして `local.default.properties` を利用します。

**例: local.default.properties（テンプレート。コミット可）**

```properties
# Android SDK のパス。空やダミー値でも構いません
sdk.dir=/path/to/Android/sdk

# 任意の Gradle JVM オプション
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
```

**例: local.properties（ローカル環境用。コミット不可）**

```properties
sdk.dir=C:\Android\Sdk          # Windows の例
# sdk.dir=/Users/you/Library/Android/sdk   # macOS の例
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

Android Studio が自動生成した `local.properties` がある場合は、それを編集して利用して構いません。

### 2. `secrets.properties` の準備

`secrets.properties` は **認証まわり・デフォルト値** を置くためのローカルファイルです（Git 管理外）。  
Credential Manager ベースの認証サンプルを使う場合のみ、いくつかのキーが必要になります。

**例: secrets.properties（コミット不可）**

```properties
# 任意: デフォルトのアップロード先フォルダ ID（UI から上書き可）
DRIVE_DEFAULT_FOLDER_ID=

# 任意: デフォルトのアップロードエンジン
UPLOAD_ENGINE=kotlin

# 任意: Drive フルスコープの有効化フラグ（現時点では未使用）
DRIVE_FULL_SCOPE=false

# サンプルアプリで Credential Manager 認証を使う場合に必要なサーバー側クライアント ID
# secrets-gradle-plugin により BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID として公開されます
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
```

これらのキーが無くても、ライブラリ自体は動作します。  
Credential Manager ベースの UI サンプルを利用したい場合のみ設定してください。

### 3. Google Drive 連携の前提条件

1. Google Cloud Console でプロジェクトを作成  
2. OAuth 同意画面を「公開」ステータスまで設定  
3. 認証情報で **Android / Web Application** の OAuth クライアント ID を作成  
4. `secrets.properties` やアプリコードにクライアント ID を反映  
5. 使用するスコープ:
   - `https://www.googleapis.com/auth/drive.file`  
   - `https://www.googleapis.com/auth/drive.metadata.readonly`

### 4. 権限の追加（確認用）

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />

<!-- 任意: Foreground Service や UI が一切無い状態でも位置情報が必要な場合のみ -->
<!-- <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" /> -->
```

### 5. ビルド

```bash
./gradlew assembleDebug
```

---

## 開発スタイル (Development)

- Kotlin コーディング規約に準拠
- UI は Jetpack Compose ベース
- DataStore + Room による永続化
- WorkManager によるバックグラウンド処理
- 表示共通処理は `Formatters.kt` などのユーティリティに集約

---

## 機能実装状況 (Feature Implementation Status)

| 機能                           | 状況       | 備考                                                |
|--------------------------------|------------|-----------------------------------------------------|
| 位置ログ記録 (Room)           | [v] 実装済 | Room DB に保存                                      |
| 日次エクスポート (GeoJSON+ZIP)| [v] 実装済 | `MidnightExportWorker` により 0 時に実行           |
| Google Drive アップロード      | [v] 実装済 | Kotlin 実装のアップローダーを利用                   |
| Pickup (間隔／件数指定)       | [v] 実装済 | ViewModel + UseCase により UI と連携                |
| Drive フルスコープ認証        | [-] 対応中 | `drive` フルスコープでの既存ファイル更新は未実装   |
| UI: DriveSettingsScreen       | [v] 実装済 | 認証・フォルダ設定・接続テスト機能あり             |
| UI: PickupScreen              | [v] 実装済 | 抽出条件入力と結果リスト表示                        |
| UI: 履歴リスト                | [v] 実装済 | 保存済みサンプルの時系列表示                        |

