# GeoLocationProvider

GeoLocationProvider は、Android 上で **位置情報を記録・保存・エクスポート** するための SDK & サンプルアプリケーションです。  
エクスポートしたデータを **Google Drive にアップロード** する機能も提供します。

---

## 機能 (Features)

- バックグラウンドでの位置情報取得（サンプリング間隔は設定可能）
- Room データベースによる永続化
- GeoJSON 形式へのエクスポート（ZIP 圧縮付き）
- 毎日 0 時に自動実行されるエクスポート（Midnight Export Worker）
- 昨日のバックアップ・今日のプレビュー用の手動エクスポート
- Pickup 機能（期間や件数などの条件に基づき代表サンプルを抽出）
- Google Drive へのアップロード（フォルダ選択付き）

---

## アーキテクチャ (Architecture)

### モジュール構成 (Module Structure)

> 本リポジトリには `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning`, `auth-*` のモジュールが含まれています。  
> サンプルアプリの構成をそのまま利用することも、必要なモジュールだけを取り出して自アプリに組み込むこともできます。

- **:core**  
  位置取得サービス (`GeoLocationService`)、センサー周辺ロジック、サンプリング間隔などの設定反映を担当。  
  永続化は `:storageservice` に委譲し、Dead Reckoning は `:deadreckoning` を利用します。

- **:storageservice**  
  Room の `AppDatabase`・DAO と、それらへの唯一の入口となる `StorageService` を提供。  
  位置ログ (`LocationSample`) と日次エクスポート状態 (`ExportedDay`) を一元管理します。

- **:dataselector**  
  Pickup などの条件に基づくデータ抽出ロジック。  
  `SelectorCondition`, `SelectedSlot`, `SelectorRepository`, `BuildSelectedSlots` などを公開します。

- **:datamanager**  
  GeoJSON へのエクスポート、ZIP 圧縮、`MidnightExportWorker` / `MidnightExportScheduler`、  
  および Google Drive 連携（`GoogleDriveTokenProvider`, `Uploader`, `DriveTokenProviderRegistry` など）を担当します。

- **:deadreckoning**  
  Dead Reckoning エンジンと API (`DeadReckoning`, `GpsFix`, `PredictedPoint`) を提供し、`GeoLocationService` から利用されます。

- **:auth-appauth / :auth-credentialmanager**  
  `GoogleDriveTokenProvider` の実装モジュール。AppAuth / Android Credential Manager + Identity による認証フローを提供します。

- **:app**  
  Jetpack Compose ベースのサンプル UI（履歴リスト、Pickup 画面、Drive 設定画面など）を実装します。

### 主なコンポーネント (Key Components)

- **LocationSample / ExportedDay エンティティ**  
  `LocationSample` は緯度・経度・タイムスタンプ・速度・バッテリー残量などを保持する位置ログです。  
  `ExportedDay` は「どの日付がエクスポート済みか」「Drive アップロードが成功したか」「エラー内容」などを記録します。

- **StorageService**  
  Room (`AppDatabase` / DAO) への「唯一の入り口」となるファサードです。  
  他モジュールは `StorageService` 経由でのみ DB にアクセスし、DAO や `AppDatabase` を直接 import しない方針です。

- **Pickup / dataselector**  
  `SelectorCondition`, `SelectorRepository`, `BuildSelectedSlots` が Pickup ロジックの中核です。  
  条件に従ってログを間引き・グリッド吸着し、欠測は `SelectedSlot(sample = null)` で表現します。

- **MidnightExportWorker & MidnightExportScheduler**  
  WorkManager を用いたバックグラウンドタスク。毎日 0 時に前日分のログを GeoJSON + ZIP にエクスポートし、  
  Google Drive へのアップロードと `ExportedDay` 状態の更新までを自動実行します。

- **GoogleDriveTokenProvider / Uploader / DriveApiClient**  
  Drive 連携の中核コンポーネントです。  
  `GoogleDriveTokenProvider` はアクセス トークン取得を抽象化し、`Uploader` / `UploaderFactory` は Drive へのアップロード処理、  
  `DriveApiClient` は `/files`・`/about` などの REST 呼び出しを扱います。  
  GoogleAuthUtil ベースのレガシー実装 `GoogleAuthRepository` も互換用として残っていますが、新規コードでは使用しません。

---

## Public API / 公開 API

GeoLocationProvider は「再利用可能なライブラリ + サンプルアプリ」という構成です。  
他アプリから再利用する際には、主に次のモジュール・クラスを公開 API として利用する想定です。

### モジュールと主なエントリポイント

- **`:core`**
  - `GeoLocationService` … 位置情報を記録する Foreground Service。`LocationSample` を DB に書き込みます。
  - `UploadEngine` … エクスポート／アップロード方式の種別を表す Enum。

- **`:storageservice`**
  - `StorageService` … Room (`LocationSample`, `ExportedDay`) への唯一のファサード。
  - `LocationSample`, `ExportedDay` … 位置ログとエクスポート状態を表すエンティティ。
  - `SettingsRepository` … サンプリング間隔・DR 間隔などの設定値を管理。

- **`:dataselector`**
  - `SelectorCondition`, `SelectedSlot`, `SortOrder` … Pickup / クエリのドメインモデル。
  - `LocationSampleSource` … `LocationSample` の取得元を抽象化するインターフェース。
  - `SelectorRepository`, `BuildSelectedSlots` … コアな選択ロジックと UI 用のユースケース。
  - `SelectorPrefs` … Pickup 条件を永続化する DataStore ファサード。

- **`:datamanager`**
  - `GeoJsonExporter` … `LocationSample` を GeoJSON + ZIP にエクスポート。
  - `GoogleDriveTokenProvider` … Drive アクセストークン取得の抽象インターフェース。
  - `DriveTokenProviderRegistry` … バックグラウンド用のトークンプロバイダ レジストリ。
  - `Uploader`, `UploaderFactory` … Drive へのアップロードを行う高レベル API。
  - `DriveApiClient`, `DriveFolderId`, `UploadResult` … Drive REST 呼び出しと結果表現。
  - `MidnightExportWorker`, `MidnightExportScheduler` … 日次エクスポートの Worker とスケジューラ。

- **`:deadreckoning`**
  - `DeadReckoning`, `GpsFix`, `PredictedPoint` … DR エンジンの公開 API（`GeoLocationService` から利用）。

- **`:auth-credentialmanager` / `:auth-appauth`（任意）**
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider` … `GoogleDriveTokenProvider` の参照実装。

DAO や `AppDatabase`、具体的な `*Repository` 実装、低レベルな HTTP ヘルパーなどはライブラリ内部の詳細であり、  
将来的に互換性なく変更される可能性があります。

---

## 最小構成の統合例 (Minimal integration example)

以下はホストアプリに組み込む際の、最小限のエンドツーエンド構成例です。

### 1. 位置記録サービスを起動し、履歴を監視する

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
// ViewModel から最新 N 件の履歴を Flow で監視
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    val latest: StateFlow<List<LocationSample>> =
        StorageService.latestFlow(app.applicationContext, limit = 100)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
```

### 2. 保存済みログに対して Pickup を実行する

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

### 3. Drive アップロードと日次エクスポートを設定する

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

より詳細な説明やフルコードは、英語版の `README.md` も併せて参照してください。

---

## クイックスタート (Quick Start)

> `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning`, `auth-*` のソースコードはすでにリポジトリに含まれており、サンプルアプリとして配線済みです。  
> ここではローカル専用ファイルの準備と、必要に応じた Google Drive 連携の前提条件だけを説明します。

### 0. 要件 (Requirements)

- AGP 8.11 以降に対応した Android Studio（または同梱の `./gradlew`）
- JDK 17（Gradle / Kotlin の JVM ターゲットが 17）
- Android SDK（少なくとも API 26 以上。`compileSdk = 36` を利用）

### 1. `local.properties` の準備

`local.properties` は **開発マシン固有の設定**（主に Android SDK のパスなど）を置くファイルです（通常は Git 管理外）。  
多くの場合は Android Studio が自動生成しますが、存在しない場合はプロジェクトルートに作成してください。

**例: `local.properties`（ローカル専用・コミットしない）**

```properties
sdk.dir=C:\\Android\\Sdk          # Windows の例
# sdk.dir=/Users/you/Library/Android/sdk   # macOS の例
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

### 2. `secrets.properties` の準備

`secrets.properties` は、`secrets-gradle-plugin` 経由で BuildConfig に埋め込む **認証・API キーなどの機密情報** を置くためのローカルファイルです（Git 管理外）。  
リポジトリにはテンプレートとして `local.default.properties` が含まれているので、これをコピーして編集する運用を想定しています。

```bash
cp local.default.properties secrets.properties   # Windows の場合はエクスプローラー等でコピー
```

**例: `local.default.properties`（テンプレート・コミット可）**

```properties
# Credential Manager (server) client ID for Google Sign-In / Identity.
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com

# AppAuth (installed app) client ID for OAuth2 with custom scheme redirect.
# Use an "installed app" client, not the server client ID above.
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com
```

**例: `secrets.properties`（ローカル専用・コミット禁止）**

```properties
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com

# サンプルアプリの Manifest から参照される Google Maps API キー（任意）
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

> ローカルでビルドするだけならダミー値でも構いません。  
> 実際に Drive 認証や地図表示を行う場合は、Cloud Console で取得した実際のクライアント ID / API キーを設定してください。

### 3. Google Drive 連携の前提条件

1. Google Cloud Console でプロジェクトを作成  
2. OAuth 同意画面を「公開」ステータスまで設定  
3. 使用する認証方式に応じて、OAuth クライアント（**Android アプリ** と **Web アプリケーション** など）を作成  
4. 発行されたクライアント ID を `secrets.properties` やアプリコードに設定  
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

<!-- 任意: Foreground Service や UI が見えていない状態でも位置取得が必要な場合のみ -->
<!-- <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" /> -->
```

### 5. ビルド

```bash
./gradlew :app:assembleDebug
```

---

## 開発スタイル (Development)

- Kotlin コーディング規約に準拠
- UI は Jetpack Compose ベース
- DataStore + Room による永続化
- WorkManager によるバックグラウンド処理
- 表示フォーマットなどは `Formatters.kt` などのユーティリティに集約

---

## 機能実装状況 (Feature Implementation Status)

| 機能                          | 状況       | 備考                                             |
|-------------------------------|------------|--------------------------------------------------|
| 位置ログ記録 (Room)           | [v] 実装済 | Room DB に保存                                   |
| 日次エクスポート (GeoJSON+ZIP)| [v] 実装済 | `MidnightExportWorker` により 0 時に実行         |
| Google Drive アップロード     | [v] 実装済 | Kotlin 実装のアップローダーを利用                |
| Pickup (間隔 / 件数指定)      | [v] 実装済 | ViewModel + UseCase により UI と連携             |
| Drive フルスコープ認証        | [-] 検討中 | `drive` フルスコープでの既存ファイル更新は未実装 |
| UI: DriveSettingsScreen       | [v] 実装済 | 認証・フォルダ設定・接続テスト等                 |
| UI: PickupScreen              | [v] 実装済 | 抽出条件入力と結果リスト表示                     |
| UI: 履歴リスト                | [v] 実装済 | 保存済みサンプルの時系列表示                     |

