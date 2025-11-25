# GeoLocationProvider (日本語版)

GeoLocationProvider は、Android 上で **位置情報を記録・保存・エクスポート・アップロード** するための SDK & サンプルアプリケーションです。  
バックグラウンドで位置情報を Room データベースに保存し、GeoJSON+ZIP 形式でエクスポートし、**Google Drive へのアップロード** も行えます。

---

## 機能概要

- バックグラウンド位置情報取得（サンプリング間隔は設定可能）
- Room データベースへの保存（`LocationSample`, `ExportedDay`）
- GeoJSON 形式へのエクスポート（ZIP 圧縮付き）
- 毎日 0 時の自動エクスポート（`MidnightExportWorker`）
- 昨日のバックアップ / 今日のプレビュー用の手動エクスポート
- Pickup 機能（期間・件数などの条件に基づく代表サンプル抽出）
- Google Drive へのアップロード（フォルダ選択付き）

---

## アーキテクチャ

### モジュール構成

本リポジトリはマルチモジュール構成の Gradle プロジェクトです:

- **`:app`** – Jetpack Compose ベースのサンプル UI（履歴一覧、Pickup 画面、Drive 設定画面、手動エクスポートなど）。
- **`:core`** – 位置取得サービス（`GeoLocationService`）、センサー処理、サンプリング間隔の適用。永続化は `:storageservice` に委譲し、DR は `:deadreckoning` を利用します。
- **`:storageservice`** – Room `AppDatabase` / DAO と `StorageService` ファサード。位置ログとエクスポート状況の唯一の入口です。
- **`:dataselector`** – Pickup 向けの選択ロジック。`LocationSample` を条件でフィルタし、代表行 `SelectedSlot` を構築します。
- **`:datamanager`** – GeoJSON エクスポート、ZIP 圧縮、`MidnightExportWorker` / `MidnightExportScheduler`、Google Drive 連携を担当します。
- **`:deadreckoning`** – Dead Reckoning エンジンと公開 API（`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`）。
- **`:auth-appauth`** – AppAuth ベースの `GoogleDriveTokenProvider` 実装。
- **`:auth-credentialmanager`** – Credential Manager + Identity ベースの `GoogleDriveTokenProvider` 実装。

依存関係の向き（概要）は次の通りです:

- `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, 認証系モジュール  
- `:core` → `:storageservice`, `:deadreckoning`  
- `:datamanager` → `:storageservice`, Drive 連携クラス  
- `:dataselector` → `LocationSampleSource` 抽象のみ（具体実装は `:app` 側で `StorageService` をラップ）

### 主なコンポーネント

- **エンティティ: `LocationSample` / `ExportedDay`**  
  `LocationSample` は緯度・経度・タイムスタンプ・速度・バッテリー残量などを保持します。  
  `ExportedDay` は日ごとのエクスポート状態（ローカル出力済みか、アップロード結果、最終エラーなど）を管理します。

- **`StorageService`**  
  Room (`AppDatabase` / DAO) への唯一の入口となるファサードです。  
  他モジュールは DAO を直接触らず、`StorageService` 経由で DB にアクセスします。

- **Pickup（`:dataselector`）**  
  `SelectorCondition`, `SelectorRepository`, `BuildSelectedSlots`, `SelectorPrefs` などが Pickup の中核です。  
  ログを間引き・グリッド吸着して代表サンプルを作成し、欠測は `SelectedSlot(sample = null)` として表現します。  
  `LocationSampleSource` がサンプルの供給元を抽象化し、サンプルアプリでは `StorageService` を使った実装を提供します。

- **Dead Reckoning（`:deadreckoning`）**  
  公開 API（`DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`）と、internal なエンジン実装（`DeadReckoningEngine`, `DeadReckoningImpl` など）で構成されます。  
  `GeoLocationService` は `DeadReckoningFactory.create(applicationContext)` でインスタンスを生成し、`start()` / `stop()` で制御します。

- **日次エクスポート（`MidnightExportWorker` / `MidnightExportScheduler`）**  
  WorkManager ベースのパイプラインで、次の処理を行います:
  - タイムゾーン `Asia/Tokyo` で「前日まで」の日付を走査
  - 各日について `StorageService.getLocationsBetween` で `LocationSample` を取得
  - `GeoJsonExporter.exportToDownloads` で GeoJSON+ZIP に変換
  - `markExportedLocal` によるローカル出力済みフラグの設定
  - `UploaderFactory` + `GoogleDriveTokenProvider` による Drive へのアップロード
  - `markUploaded` / `markExportError` によるアップロード状態の記録
  - ZIP ファイルの削除、およびアップロード成功時の古いログ削除

- **Drive 連携（`GoogleDriveTokenProvider` など）**  
  `GoogleDriveTokenProvider` が Drive アクセストークン取得を抽象化し、  
  `DriveTokenProviderRegistry` がバックグラウンド用のプロバイダを提供します。  
  `Uploader` / `UploaderFactory`（実体は `KotlinDriveUploader`）がアップロードを行い、`DriveApiClient` が `/files` / `/about` などの REST 呼び出しを担当します。  
  旧来の `GoogleAuthRepository`（GoogleAuthUtil ベース）は後方互換のために残していますが、新規コードでは非推奨です。

- **UI（Compose）– `MainActivity`, `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen`**  
  UI は 2 段構えの `NavHost` で構成されています。
  - Activity レベル: `"home"`, `"drive_settings"`（AppBar の Drive メニューから Drive 設定へ遷移）
  - アプリレベル: `"home"`, `"pickup"`（AppBar のボタンで Home / Pickup を切り替え）  
  パーミッションは `ActivityResultContracts.RequestMultiplePermissions` で取得し、サービスの Start/Stop トグルは `ServiceToggleAction` にまとめています。

---

## 公開 API 概要

GeoLocationProvider は、

- そのまま動かせる **サンプルアプリ**（`:app`）と
- 自作アプリに組み込める **ライブラリモジュール群**

として設計されています。ライブラリを利用する際によく依存する型は次の通りです。

### モジュールと主な入口

- **`:core`**
  - `GeoLocationService` – 位置ログ（`LocationSample`）を記録するフォアグラウンドサービス。
  - `UploadEngine` – 使用するアップロードエンジンを表す enum。

- **`:storageservice`**
  - `StorageService` – Room（`LocationSample`, `ExportedDay`）のファサード。
  - `LocationSample`, `ExportedDay` – 位置ログと日次エクスポート状態の主要エンティティ。
  - `SettingsRepository` – サンプリング / Dead Reckoning 間隔の設定。

- **`:dataselector`**
  - `SelectorCondition`, `SelectedSlot`, `SortOrder` – Pickup / クエリのドメインモデル。
  - `LocationSampleSource` – `LocationSample` の取得元を抽象化するインターフェース。
  - `SelectorRepository`, `BuildSelectedSlots` – 選択ロジックと小さな UseCase ラッパー。
  - `SelectorPrefs` – Pickup 条件を永続化する DataStore ラッパー。

- **`:datamanager`**
  - `GeoJsonExporter` – `LocationSample` を GeoJSON + ZIP にエクスポート。
  - `GoogleDriveTokenProvider` – Drive アクセストークンの抽象。
  - `DriveTokenProviderRegistry` – バックグラウンド用トークンプロバイダのレジストリ。
  - `Uploader`, `UploaderFactory` – Drive アップロードの高レベルエントリポイント。
  - `DriveApiClient`, `DriveFolderId`, `UploadResult` – Drive REST ヘルパーと結果モデル。
  - `MidnightExportWorker`, `MidnightExportScheduler` – 日次エクスポートのパイプライン。

- **`:deadreckoning`**
  - `DeadReckoning`, `GpsFix`, `PredictedPoint` – `GeoLocationService` から利用される DR エンジン API。
  - `DeadReckoningConfig`, `DeadReckoningFactory` – DR インスタンスを生成するための設定とファクトリ。

- **`:auth-credentialmanager` / `:auth-appauth`**（任意）
  - `CredentialManagerTokenProvider`, `AppAuthTokenProvider` – `GoogleDriveTokenProvider` の参照実装。

これ以外の型（DAO や `AppDatabase` 実装、各種 Repository 実装、低レベル HTTP ヘルパーなど）は **内部実装** とみなし、予告なく変更される可能性があります。

---

## 最小統合例

自前アプリに組み込む際の最小例を示します。

### 1. 位置サービスを開始し、履歴を監視する

```kotlin
// Activity 内
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
// ViewModel 内
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    val latest: StateFlow<List<LocationSample>> =
        StorageService.latestFlow(app.applicationContext, limit = 100)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
```

### 2. 保存済みサンプルに対して Pickup（間引き・代表抽出）を実行する

```kotlin
// StorageService バックエンドの LocationSampleSource 実装
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
        fromMillis = /* startMs */,
        toMillis = /* endMs */,
        intervalSec = 60,         // 60 秒グリッド
        limit = 1000,
        order = SortOrder.OldestFirst
    )
    return useCase(cond)
}
```

### 3. Drive アップロードと日次エクスポートを設定する

```kotlin
// Application クラス内
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 例: :auth-credentialmanager の CredentialManagerTokenProvider を再利用
        val provider = CredentialManagerTokenProvider(
            context = this,
            serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
        )

        // MidnightExportWorker 用のバックグラウンドプロバイダを登録
        DriveTokenProviderRegistry.registerBackgroundProvider(provider)

        // 日次エクスポートを 0 時にスケジュール
        MidnightExportScheduler.scheduleNext(this)
    }
}
```

```kotlin
// 任意のファイルを Drive にアップロードする例（オプション）
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

---

## Google Drive 認証オプション

すべての Drive アップロードは `GoogleDriveTokenProvider` を通じて行われます。基本的な契約は次の通りです。

- トークンは `"Bearer "` なしの **生のアクセストークン文字列** を返す。
- 通常の失敗（ネットワークエラー・未サインイン・同意不足など）は、例外ではなく **`null` を返す** ことで表現する。
- バックグラウンドコンテキストから UI を直接起動してはいけない。UI が必要な場合は `null` を返し、呼び出し元が判断する。

以下の実装から選ぶか、自前実装を用意できます。

### オプション 1: AppAuth（標準 OAuth 2.0 / PKCE）

AppAuth for Android を利用した、標準的な OAuth 2.0（ネイティブアプリ + PKCE）です。

1) アプリモジュールに依存関係を追加:

```gradle
implementation(project(":auth-appauth"))
```

2) Google Cloud Console で OAuth 2.0 クライアントを作成:

- OAuth 同意画面を設定する。
- **インストールアプリ**（Android / その他インストールアプリ）としてクライアント ID を作成し、Web アプリ用クライアントは使わない。
- カスタム URI スキーム（例: `com.mapconductor.plugin.provider.geolocation`）を許可する。
- 次のようなリダイレクト URI を登録する:

```text
com.mapconductor.plugin.provider.geolocation:/oauth2redirect
```

3) トークンプロバイダを初期化（例）:

```kotlin
val tokenProvider = AppAuthTokenProvider(
    context = applicationContext,
    clientId = BuildConfig.APPAUTH_CLIENT_ID,
    redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
)

// Activity から認可フローを開始
val intent = tokenProvider.buildAuthorizationIntent()
startActivityForResult(intent, REQUEST_CODE)

// 結果を受け取る
tokenProvider.handleAuthorizationResponse(data)

// Uploader と組み合わせて利用
val uploader = KotlinDriveUploader(context, tokenProvider)
```

サンプルプロジェクトでは、`APPAUTH_CLIENT_ID` は `secrets.properties` 内の `APPAUTH_CLIENT_ID` から `secrets-gradle-plugin` により生成されます。

### オプション 2: Credential Manager（モダンな Android 認証 / サンプルアプリ既定）

Android Credential Manager + Identity API を用いた認証です。サンプルアプリではこちらを既定の経路として利用しています（Android 14+ との相性が良好です）。

1) アプリモジュールに依存関係を追加:

```gradle
implementation(project(":auth-credentialmanager"))
```

2) Google Cloud Console で OAuth 2.0 クライアントを作成:

- **Web アプリケーション** としてクライアント ID を作成します。
- 得られたクライアント ID を **サーバー用クライアント ID** として扱います。

3) トークンプロバイダを初期化:

```kotlin
val tokenProvider = CredentialManagerTokenProvider(
    context = applicationContext,
    serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
)

// Activity / UI からサインイン
val credential = tokenProvider.signIn()

// アクセストークン取得（バックグラウンド利用も可能）
val token = tokenProvider.getAccessToken()
```

サンプルアプリでは:

- `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` は `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID` から参照されます。
- この値は `secrets.properties` の `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` に基づき、`secrets-gradle-plugin` によって生成されます。
- `App` が `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))` を呼び出し、バックグラウンド用プロバイダとして登録します。

### オプション 3: レガシー実装（後方互換のみ）

`GoogleAuthRepository`（GoogleAuthUtil ベース）は互換性のために残っていますが、新規コードでは **非推奨** です。

```kotlin
@Deprecated("Use AppAuth or Credential Manager instead")
val tokenProvider = GoogleAuthRepository(context)
```

### 必要な OAuth スコープ

どの実装を使う場合も、次の Drive スコープを利用します。

- `https://www.googleapis.com/auth/drive.file` – アプリが作成 / 開いたファイルへのアクセス。
- `https://www.googleapis.com/auth/drive.metadata.readonly` – フォルダ ID 検証やメタデータ取得。

### 独自 `GoogleDriveTokenProvider` を実装する場合

```kotlin
class MyCustomTokenProvider : GoogleDriveTokenProvider {
    override suspend fun getAccessToken(): String? {
        // 独自実装
    }

    override suspend fun refreshToken(): String? {
        // 独自実装
    }
}
```

---

## クイックスタート

> `app`, `core`, `dataselector`, `datamanager`, `storageservice`, `deadreckoning`, `auth-*` のソースはすでに本リポジトリ内で配線済みです。  
> ローカル設定ファイルと（必要であれば）Google Drive 向けの認証情報を用意すれば、サンプルアプリをそのままビルド・実行できます。

### 0. 必要環境

- Android Studio（AGP 8.1+ 対応）または同梱の `./gradlew`
- JDK 17（Gradle / Kotlin の JVM ターゲット 17）
- Android SDK API 26 以上（プロジェクトは `compileSdk = 36` を使用）

### 1. `local.properties` の準備

`local.properties` には **開発マシン固有の設定**（Android SDK のパスなど）を記述します（通常は Git 管理対象外）。  
Android Studio が自動生成するのが一般的ですが、存在しない場合はプロジェクトルートに作成してください。

**例: `local.properties`（ローカル専用・コミットしない）**

```properties
sdk.dir=C:\\Android\\Sdk          # Windows の例
# sdk.dir=/Users/you/Library/Android/sdk   # macOS の例
org.gradle.jvmargs=-Xmx6g -XX:+UseParallelGC
```

### 2. `secrets.properties` の準備

`secrets.properties` は `secrets-gradle-plugin` 経由で BuildConfig に埋め込む **認証情報やその他の機密値** を格納するファイルです。  
リポジトリにはテンプレートとして `local.default.properties` が含まれているので、これをコピーして値を設定します。

```bash
cp local.default.properties secrets.properties   # Windows の場合はエクスプローラー等でコピーしても可
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

# サンプルアプリの Manifest で参照される Google Maps API キー（任意）
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

ローカルでビルドして動作確認するだけであれば、ダミー値でも問題ありません。  
実際に Drive 認証や Maps 表示を行う場合は、Cloud Console で取得した実際のクライアント ID / API キーを設定してください。

### 3. Google Drive 連携の前提設定

Drive アップロードを利用するには、Google Cloud Console で次の設定を行います。

1. プロジェクト作成と OAuth 同意画面の設定  
2. OAuth クライアント ID の作成  
   - Credential Manager 用: **Web アプリケーション** クライアント（`CREDENTIAL_MANAGER_SERVER_CLIENT_ID`）  
   - AppAuth 用: **インストールアプリ** クライアント（`APPAUTH_CLIENT_ID`、カスタムスキーム + リダイレクト URI）  
3. Drive API を有効化し、次のスコープを許可:
   - `https://www.googleapis.com/auth/drive.file`
   - `https://www.googleapis.com/auth/drive.metadata.readonly`
4. 上記クライアント ID を `secrets.properties` に設定

### 4. パーミッションの追加（確認用）

```xml
<!-- app/src/main/AndroidManifest.xml -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />

<!-- Foreground Service や UI が見えていない状態でも位置情報が必要な場合のみ -->
<!-- <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" /> -->
```

### 5. ビルド

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

---

## 機能実装状況

| 機能                           | 状況         | 備考                                          |
|--------------------------------|--------------|-----------------------------------------------|
| 位置ログ記録（Room）           | [v] 実装済み | Room DB に保存                                |
| 日次エクスポート（GeoJSON+ZIP）| [v] 実装済み | `MidnightExportWorker` により 0 時に実行      |
| Google Drive アップロード      | [v] 実装済み | Kotlin ベースの Uploader を利用               |
| Pickup（間隔 / 件数指定）      | [v] 実装済み | ViewModel + UseCase + Compose UI で構成       |
| Drive フルスコープ認証         | [-] 検討中   | 既存ファイルのフルブラウズ / 更新は未サポート |
| UI: DriveSettingsScreen        | [v] 実装済み | 認証・フォルダ設定・接続テスト                |
| UI: PickupScreen               | [v] 実装済み | 抽出条件入力と結果表示                        |
| UI: 履歴リスト                 | [v] 実装済み | 保存済みサンプルの時系列表示                  |

