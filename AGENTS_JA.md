# Repository Guidelines (日本語版)

GeoLocationProvider リポジトリで作業する際の **共通方針・コーディング規約・モジュール間の責務分担** をまとめたドキュメントです。  
コードを変更する前に一度読んでいただき、ここに書かれた方針に沿って実装してください。

---

## プロジェクト構成とモジュール

- ルート Gradle プロジェクト `GeoLocationProvider` は次のモジュールで構成されています。
  - `:app` – Compose ベースのサンプル UI アプリ（履歴一覧・Pickup・Drive 設定・手動バックアップなど）。
  - `:core` – 位置情報取得サービス（`GeoLocationService`）とセンサー周辺ロジック。永続化は `:storageservice` に委譲します。
  - `:storageservice` – Room `AppDatabase` / DAO と `StorageService` ファサード。位置ログとエクスポート状態を一元管理します。
  - `:dataselector` – Pickup などの選択ロジック。`LocationSample` を条件で絞り込み、代表サンプル行（`SelectedSlot`）を構築します。
  - `:datamanager` – GeoJSON へのエクスポート、ZIP 圧縮、`MidnightExportWorker` / `MidnightExportScheduler`、Google Drive 連携を担当します。
  - `:deadreckoning` – Dead Reckoning エンジンおよび API。`GeoLocationService` から利用されます。
  - `:auth-appauth` – AppAuth ベースの `GoogleDriveTokenProvider` 実装を提供するライブラリモジュール。
  - `:auth-credentialmanager` – Credential Manager + Identity ベースの `GoogleDriveTokenProvider` 実装を提供するライブラリモジュール。

- 依存関係のざっくりした向き:
  - `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, 認証系モジュール
  - `:core` → `:storageservice`, `:deadreckoning`
  - `:datamanager` → `:storageservice`, Drive 連携クラス
  - `:dataselector` → `LocationSampleSource` 抽象のみ（具体実装は `:app` 側で `StorageService` をラップ）

- 本番コードは各モジュールの `src/main/java`, `src/main/kotlin`, `src/main/res` に配置します。  
  ビルド成果物は各モジュール配下の `build/` 以下に生成されます。

- 開発マシン固有の設定（Android SDK のパスなど）はルートの `local.properties` に保存します。  
  通常は Android Studio が自動生成しますが、存在しない場合は手動で作成してください。

- 認証まわりなどの機密情報は `secrets.properties` に保存し、このファイルは **Git 管理対象にしない** でください。  
  テンプレートとして `local.default.properties` が用意されているので、これをコピーして実際の値に置き換えてください。

---

## ビルド・テスト・開発用コマンド

- 代表的な Gradle コマンド（ルートディレクトリで実行）:
  - `./gradlew :app:assembleDebug` – サンプルアプリの Debug APK をビルドします。
  - `./gradlew :core:assemble` / `./gradlew :storageservice:assemble` – ライブラリモジュールをビルドします。
  - `./gradlew lint` – Android / Kotlin の静的解析を実行します。
  - `./gradlew test` / `./gradlew :app:connectedAndroidTest` – ユニットテスト / UI・計測テストを実行します。

- 日常開発では Android Studio からのビルド・実行を基本とし、CI や一括検証に Gradle コマンドラインを利用します。

---

## コーディングスタイルと命名規約

- 主な技術スタック: Kotlin, Jetpack Compose, Gradle Kotlin DSL  
  インデントは 4 スペース、ソースコードの文字コードは UTF-8 を前提とします。

- パッケージ構成の目安:
  - アプリ / サービス層: `com.mapconductor.plugin.provider.geolocation.*`
  - ストレージ層: `com.mapconductor.plugin.provider.storageservice.*`
  - Dead Reckoning: `com.mapconductor.plugin.provider.geolocation.deadreckoning.*`

- 命名規約:
  - クラス / オブジェクト / インターフェース – PascalCase
  - 変数 / プロパティ / 関数 – camelCase
  - 定数 – UPPER_SNAKE_CASE
  - 画面レベルの Composable – `*Screen`
  - ViewModel – `*ViewModel`
  - Worker / Scheduler – `*Worker` / `*Scheduler`

- 関数は 1 つの責務に絞り、読みやすさを最優先する名前を付けてください。

- 未使用の import や使われていないコードを見つけた場合は削除してください。

- KDoc / コメントでは、次の観点を明示することを推奨します:
  - 役割・責務
  - 設計方針
  - 想定する利用方法
  - 契約（呼び出し側が期待できること / できないこと）

---

## レイヤリングと責務分担

### StorageService / Room アクセス

- Room の `AppDatabase` / DAO へのアクセスは基本的に `StorageService` 経由で行います。
  - 例外は `:storageservice` モジュール内部のみ（DAO 実装や `AppDatabase` 自体）。
  - `:app`, `:core`, `:datamanager`, `:dataselector` から DAO 型を直接 import しないでください。

- 主な `StorageService` の契約:
  - `latestFlow(ctx, limit)` – 最新 `limit` 件の `LocationSample` を新しい順で Flow として返します。
  - `getAllLocations(ctx)` – すべての位置情報を `timeMillis` 昇順で返します（小規模データ向け、エクスポートやプレビューなど）。
  - `getLocationsBetween(ctx, from, to, softLimit)` – `[from, to)`（半開区間）かつ `timeMillis` 昇順でレコードを取得します。  
    大規模データの取得にはこの API を使ってください。
  - `insertLocation(ctx, sample)` – 位置サンプルを 1 件挿入し、前後の件数を `DB/TRACE` タグでログ出力します。
  - `deleteLocations(ctx, items)` – 空リストなら no-op。例外はそのまま呼び出し元に伝播させます。
  - `ensureExportedDay` / `oldestNotUploadedDay` / `markExportedLocal` / `markUploaded` / `markExportError` – 日単位のエクスポート状態を管理します。

- DB アクセスはすべて `Dispatchers.IO` 上で実行される前提です。  
  呼び出し側で改めてディスパッチャを切り替える必要はありませんが、UI スレッドをブロックしないようにしてください。

### dataselector / Pickup

- `:dataselector` モジュールは `LocationSampleSource` インターフェースのみに依存し、Room や `StorageService` 自体は知りません。
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)` は `[from, to)` の半開区間を前提とします。

- `SelectorRepository` のポリシー:
  - `intervalSec == null` の場合: グリッドなしの「そのまま抽出」モード。簡易な間引きとソートのみを行います。
  - `intervalSec != null` の場合: グリッド吸着モード。
    - `T = intervalSec * 1000L` とするとき、各グリッドごとに `±T/2` の窓の中から代表サンプルを 1 つだけ選択します（複数ある場合は早い方を優先）。
    - `SortOrder.NewestFirst` の場合:
      - 終了時刻側（To, endInclusive）を基準に `buildTargetsFromEnd(from, to, T)` でグリッドを生成し、`from` より前のグリッドは作りません。
      - 一度古い順に並べた上で `slots.asReversed()` で反転し、新しい順に返します。
    - `SortOrder.OldestFirst` の場合:
      - 開始時刻側（From, startInclusive）を基準に `buildTargetsFromStart(from, to, T)` でグリッドを生成し、`to` より後のグリッドは作りません。
      - 結果はそのまま昇順（古い → 新しい）で返します。

- `SelectorPrefs` は Pickup 条件を永続化するための DataStore ラッパーです。  
  `SelectorCondition` と同じ単位（from/to はミリ秒、`intervalSec` は秒）で保持します。

- UI（`:app`）は `SelectorUseCases.buildSelectedSlots(context)` を通じて dataselector を利用します。  
  `PickupScreen` は DB / DAO 型を知らずに Pickup の結果だけを受け取ります。

### GeoLocationService / Dead Reckoning

- 実際の位置情報取得は `:core` の `GeoLocationService` が担当し、フォアグラウンドサービス（FGS）として動作します。
  - GNSS: `FusedLocationProviderClient` と `LocationRequest` を使用します。
  - IMU / Dead Reckoning: `HeadingSensor`, `GnssStatusSampler`, `DeadReckoning` を使用します。
  - 設定: `SettingsRepository.intervalSecFlow` / `drIntervalSecFlow` を購読し、サンプリング間隔などを動的に更新します。

- Dead Reckoning API（`:deadreckoning` モジュール）:
  - 公開インターフェース: `DeadReckoning`, `GpsFix`, `PredictedPoint`（`...deadreckoning.api` パッケージ）。
  - 設定オブジェクト: `DeadReckoningConfig` –  
    `staticAccelVarThreshold`, `staticGyroVarThreshold`, `processNoisePos`, `velocityGain`, `windowSize` などをまとめたパラメータオブジェクトです。
  - ファクトリ: `DeadReckoningFactory.create(context, config = DeadReckoningConfig())`  
    呼び出し側は実装詳細に依存せず `DeadReckoning` を生成できます。
  - 実装（`DeadReckoningImpl`）とエンジン（`DeadReckoningEngine`, `SensorAdapter`, `DrState`, `DrUncertainty`）は internal であり、将来的に差し替え可能です。

- `GeoLocationService` 内の利用方針:
  - `DeadReckoningFactory.create(applicationContext)` で DR インスタンスを生成し、`start()` / `stop()` に連動させます。
  - DR サンプルの挿入ポリシーや GNSS サンプルとの協調（ロック処理など）は `DeadReckoning` API の外側で扱います。

### SettingsRepository による設定管理

- サンプリング間隔・DR 間隔は `storageservice.prefs.SettingsRepository` が管理します。
  - `intervalSecFlow(context)` / `drIntervalSecFlow(context)` は **秒単位** の Flow を返し、デフォルト値や最小値の扱いを内部で行います。
  - `currentIntervalMs(context)` / `currentDrIntervalSec(context)` も用意されていますが、  
    新しいコードでは Flow ベースの API を優先してください。

---

## 認証・Drive 連携

### GoogleDriveTokenProvider と実装モジュール

- Drive 連携用の主な認証インターフェースは `GoogleDriveTokenProvider` です。
  - 新しいコードでは基本的に `CredentialManagerTokenProvider`（`:auth-credentialmanager`）  
    または `AppAuthTokenProvider`（`:auth-appauth`）のいずれかを利用してください。
  - 旧来の `GoogleAuthRepository`（GoogleAuthUtil ベース）は後方互換のために残していますが、新規機能で使用してはいけません。

- `GoogleDriveTokenProvider` 実装のポリシー:
  - 戻り値のトークン文字列には **"Bearer " プレフィックスを含めない** でください。  
    Authorization ヘッダ構築側で付与します。
  - ネットワークエラー・未サインイン・同意不足などの「通常の失敗」は、**例外を投げず** ログ出力したうえで `null` を返します。
  - UI フローが必要な場合でも、プロバイダ側から Activity / UI を直接起動してはいけません。  
    `null` を返して呼び出し元（Activity / Compose）がどうするか判断します。

### Credential Manager 認証

- `CredentialManagerAuth` は `CredentialManagerTokenProvider` を包むシングルトンです。
  - `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID` 経由で `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` を受け取り、  
    これを Google Identity の **Web / サーバー側クライアント ID** として扱います。
  - この ID は AppAuth 用クライアント ID とは異なるものであり、混在させないでください。

### AppAuth 認証（AppAuthTokenProvider / AppAuthAuth）

- `AppAuthTokenProvider` は `:auth-appauth` モジュールに含まれる `GoogleDriveTokenProvider` 実装です。

- そのラッパーである `AppAuthAuth` は次のように初期化されます。
  - `clientId = BuildConfig.APPAUTH_CLIENT_ID`
  - `redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"`

- `APPAUTH_CLIENT_ID` は `secrets.properties` の `APPAUTH_CLIENT_ID` エントリから `secrets-gradle-plugin` により生成されます。

#### Cloud Console 側の前提

- AppAuth 用クライアント ID は **インストールアプリ（Android / その他のインストールアプリ）** として作成してください。
  - Credential Manager 用の `CREDENTIAL_MANAGER_SERVER_CLIENT_ID`（Web / サーバークライアント）を流用してはいけません。

- AppAuth クライアントの必須設定:
  - カスタム URI スキーム `com.mapconductor.plugin.provider.geolocation` を許可する。
  - リダイレクト URI として  
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect`  
    を登録する。

#### AppAuthSignInActivity

- `AppAuthSignInActivity` は AppAuth のサインインフローを起動し、その結果を受け取る透明な Activity です。
  - `buildAuthorizationIntent()` でブラウザ / Custom Tab を開き、戻りの `Intent` を `handleAuthorizationResponse()` に橋渡しします。

- Manifest 設定:
  - `android:exported="true"`
  - Intent filter:
    - `scheme="com.mapconductor.plugin.provider.geolocation"`
    - `path="/oauth2redirect"`

### DriveTokenProviderRegistry / バックグラウンド

- バックグラウンドアップロード（`MidnightExportWorker` など）では:
  - UI フローを絶対に開始せず、`GoogleDriveTokenProvider.getAccessToken()` が `null` を返した場合は「未認可」とみなします。
  - Worker は ZIP ファイルを削除しますが、Room のレコードは削除せず、`ExportedDay.lastError` にエラーメッセージを保存します。

- バックグラウンドで使用するトークンプロバイダは `DriveTokenProviderRegistry.registerBackgroundProvider(...)` で登録します。  
  バックグラウンドのアップロード処理は必ずこのレジストリからプロバイダを取得してください。

- `DriveTokenProviderRegistry` はアプリプロセス内でシングルトンのように振る舞います。
  - `App.onCreate()` 内で `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))` を呼び出してください。

### Drive 設定の永続化（AppPrefs / DrivePrefsRepository）

- Drive 関連の設定は 2 系統で管理されます。
  - `core.prefs.AppPrefs` – SharedPreferences ベースのレガシー設定。`UploadEngine` と `folderId` を保持し、主に Worker や旧パスから参照します。
  - `datamanager.prefs.DrivePrefsRepository` – DataStore ベースの新しい設定。  
    `folderId`, `resourceKey`, `accountEmail`, `uploadEngine`, `authMethod`, `tokenUpdatedAtMillis` などを Flow で管理します。

- 新しいコードの方針:
  - UI / UseCase から Drive 設定を読む場合は `DrivePrefsRepository` を優先してください。
  - Worker など、レガシーコードとの互換性が必要な箇所のみ `AppPrefs` を利用します。
  - たとえば `DriveSettingsViewModel.validateFolder()` など、UI で設定を確定するタイミングでは `DrivePrefsRepository` への保存に加え、  
    `AppPrefs.saveFolderId` / `saveEngine` も呼び出してレガシーパスと設定を共有します。

---

## WorkManager / MidnightExportWorker

- `MidnightExportWorker` は「前日までのバックログ」を処理する Worker です。
  - タイムゾーンは `ZoneId.of("Asia/Tokyo")` を使用し、1 日分を `[0:00, 24:00)`（ミリ秒）の区間として扱います。
  - 初回実行時には過去 365 日分の `ExportedDay` レコードを `StorageService.ensureExportedDay` でシードします。
  - 各日について `StorageService.getLocationsBetween` で `LocationSample` を取得し、`GeoJsonExporter.exportToDownloads` で GeoJSON + ZIP に変換します。

- アップロードと削除のポリシー:
  - ローカル ZIP の出力が成功した時点で `markExportedLocal` を呼びます（その日が空でも成功扱い）。
  - Drive へのアップロードは `AppPrefs.snapshot` から現在の設定を読み、エンジンとフォルダ ID がそろっている場合のみ実行します。
  - HTTP エラーや認証エラーなどで失敗した場合は `markExportError` を呼び、`lastError` にメッセージを保存します。
  - 成否にかかわらず ZIP ファイルは必ず削除し、ローカルストレージの肥大化を防ぎます。
  - アップロードが成功し、その日にレコードが存在する場合のみ `StorageService.deleteLocations` を呼び、その日分のログを削除します。

---

## UI / Compose ガイドライン（`:app`）

- UI 層は Jetpack Compose を用い、次のパターンに沿って実装します。
  - 画面レベルの Composable: `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen` など。
  - `ViewModel` は `viewModel()` または `AndroidViewModel` で生成し、`viewModelScope` で非同期処理を行います。
  - 状態は `StateFlow` / `uiState` として公開し、Compose に渡します。

- `App` / `MainActivity` / `AppRoot`:
  - アプリケーション・クラス `App` の `onCreate()` で  
    `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))` を呼び、バックグラウンド用 Drive トークンプロバイダを登録します。
  - 同じく `App.onCreate()` で `MidnightExportScheduler.scheduleNext(this)` を呼び、日次エクスポート Worker の起動をスケジュールします。
  - `MainActivity` では `ActivityResultContracts.RequestMultiplePermissions` を用いて権限をリクエストし、許可後に `GeoLocationService` を起動します。
  - 画面遷移は 2 段階の `NavHost` 構成で行います。
    - Activity 直下の `NavHost` は `"home"` / `"drive_settings"` を持ち、AppBar の Drive メニューから Drive 設定画面に遷移します。
    - `AppRoot` 内部の `NavHost` は `"home"` / `"pickup"` を持ち、Home 画面と Pickup 画面を切り替えます（AppBar の Pickup ボタンから）。
  - AppBar は Drive 設定画面・Pickup 画面へのナビゲーションを提供し、サービスの Start/Stop トグルは `ServiceToggleAction` にまとめます。

---

## テスト・セキュリティ・その他

- ユニットテストは `src/test/java`、計測 / Compose UI テストは `src/androidTest/java` に配置します。
  - Drive 連携のテストでは `GoogleDriveTokenProvider` と HTTP クライアントをモックしてテストしてください。

- `local.properties`, `secrets.properties`, `google-services.json` などの機密ファイルは Git にコミットしないでください。  
  テンプレート + ローカルファイルで運用します。

- Google 認証や Drive 連携の挙動を変更した場合は、README（英語 / 日本語 / スペイン語）に記載された OAuth スコープやリダイレクト URI が  
  Cloud Console の設定と一致していることを確認してください。

- AppAuth と Credential Manager では **別々のクライアント ID** を使用します。
  - Credential Manager 用 – `CREDENTIAL_MANAGER_SERVER_CLIENT_ID`（Web / サーバークライアント）。
  - AppAuth 用 – `APPAUTH_CLIENT_ID`（インストールアプリ + カスタム URI スキーム）。
  - これらを混在させると `invalid_request`（例: "Custom URI scheme is not enabled / not allowed"）などのエラーの原因になります。

---

## 公開 API サーフェス（ライブラリ）

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`
  - `LocationSampleSource`, `SelectorRepository`, `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`:
  - 認証 / トークン: `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - Drive 設定: `DrivePrefsRepository`
  - Drive API: `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - アップロード・エクスポート: `Uploader`, `UploaderFactory`, `GeoJsonExporter`
  - Worker: `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`

ここに列挙されていない型は、可能な限り `internal` などで非公開のままにし、  
ライブラリとしての公開バイナリ API を小さく安定したものに保ってください。

