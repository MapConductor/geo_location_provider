# Repository Guidelines

GeoLocationProvider リポジトリで作業する際の共通方針・コーディング規約・モジュール間の責務分担をまとめたものです。
コードを変更する前に一度目を通し、内容に沿って実装してください。

---

## プロジェクト構成とモジュール

- ルート Gradle プロジェクト `GeoLocationProvider` は、以下のモジュールで構成されています。
  - `:app` … Compose ベースのサンプル UI アプリ（履歴・Pickup・Drive 設定・手動バックアップなど）。
  - `:core` … 位置情報取得サービス（`GeoLocationService`）やセンサー周辺のロジック。永続化は `:storageservice` に委譲します。
  - `:storageservice` … Room `AppDatabase` と DAO / `StorageService` ファサード。位置ログとエクスポート状態を一元管理します。
  - `:dataselector` … Pickup などの選択ロジック。`LocationSample` を条件で絞り込み、代表サンプル列（`SelectedSlot`）を構築します。
  - `:datamanager` … GeoJSON へのエクスポート、ZIP 圧縮、`MidnightExportWorker`・`MidnightExportScheduler`、Google Drive 連携などを担当します。
  - `:deadreckoning` … Dead Reckoning エンジンと API。`GeoLocationService` から利用されます。
  - `:auth-appauth` … AppAuth ベースの `GoogleDriveTokenProvider` 実装ライブラリ。
  - `:auth-credentialmanager` … Credential Manager + Identity ベースの `GoogleDriveTokenProvider` 実装ライブラリ。

- 依存関係のざっくりした向き
  - `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, 認証系モジュール。
  - `:core` → `:storageservice`, `:deadreckoning`。
  - `:datamanager` → `:storageservice`, Drive 連携クラス。
  - `:dataselector` → `LocationSampleSource` 抽象のみ（実体は `:app` 側で `StorageService` をラップ）。

- 本番コードは各モジュールの `src/main/java`・`src/main/kotlin`・`src/main/res` に配置し、ビルド成果物は各モジュールの `build/` 以下に生成されます。

- ローカル固有設定（主に Android SDK パスなど）はルートの `local.properties` に記述します。通常は Android Studio が自動生成しますが、存在しない場合は手動で作成してください。
- 機密情報や認証まわりの設定は `secrets.properties` に記述し、このファイルは **Git 管理対象にしない** でください。
  そのテンプレートとして `local.default.properties` を用意しているので、これを `secrets.properties` にコピーして値を書き換える運用を想定しています。

---

## ビルド・テスト・開発コマンド

- 代表的な Gradle コマンド（ルートで実行）:
  - `./gradlew :app:assembleDebug` … サンプルアプリの Debug APK をビルド。
  - `./gradlew :core:assemble` / `./gradlew :storageservice:assemble` … ライブラリ単体ビルド。
  - `./gradlew lint` … Android / Kotlin の静的解析を実行。
  - `./gradlew test` / `./gradlew :app:connectedAndroidTest` … ユニットテスト / UI・計測テストを実行。

- 日常開発では Android Studio からのビルド・実行を基本とし、CI や検証用途でコマンドラインを利用します。

---

## コーディングスタイルと命名規約

- 使用する主な技術は Kotlin / Jetpack Compose / Gradle Kotlin DSL です。インデントは 4 スペース、ソースコードの文字コードは UTF-8 を前提とします。

- パッケージ構成の目安:
  - アプリ本体・サービス系: `com.mapconductor.plugin.provider.geolocation.*`
  - ストレージ層: `com.mapconductor.plugin.provider.storageservice.*`
  - Dead Reckoning 系: `com.mapconductor.plugin.provider.geolocation.deadreckoning.*`

- 命名規約:
  - クラス / オブジェクト / インターフェース … PascalCase
  - 変数 / プロパティ / 関数 … camelCase
  - 定数 … UPPER_SNAKE_CASE
  - Compose 画面 Composable … `〜Screen`
  - ViewModel … `〜ViewModel`
  - Worker / Scheduler … `〜Worker` / `〜Scheduler`

- 関数は 1 つの責務に絞り、読みやすさを優先した名前を付けます。

- 未使用 import やデッドコードは見つけ次第削除してください。

- KDoc / コメントでは、「■役割」「■方針」「想定用途」「契約」といった観点を明示するスタイルを推奨します。

---

## レイヤリングと責務分担

### StorageService / Room アクセス

- Room の `AppDatabase` / DAO には、原則として `StorageService` 経由でアクセスします。
  - 例外は `:storageservice` モジュール内部のみ（DAO 実装や `AppDatabase` 自体）。
  - `:app` / `:core` / `:datamanager` / `:dataselector` から DAO 型を直接 import しないでください。

- `StorageService` の主な契約:
  - `latestFlow(ctx, limit)` … 最新 `limit` 件の `LocationSample` を「新しいものが先頭」の降順で Flow 監視。
  - `getAllLocations(ctx)` … `timeMillis` 昇順で全件取得。少量データ向け。一括エクスポートやプレビュー用途。
  - `getLocationsBetween(ctx, from, to, softLimit)` … `[from, to)` の半開区間で `timeMillis` 昇順に取得。大量データにはこの API を使う。
  - `insertLocation(ctx, sample)` … 1 件挿入し、前後の件数を `DB/TRACE` タグでログ出力。
  - `deleteLocations(ctx, items)` … 空リストなら何もしない・例外はそのままスロー。
  - `ensureExportedDay` / `oldestNotUploadedDay` / `markExportedLocal` / `markUploaded` / `markExportError` … 日次エクスポート状態の管理。

- DB アクセスはすべて `Dispatchers.IO` で実行される前提なので、呼び出し側で追加の Dispatcher 切り替えは不要です（UI スレッドから直接ブロッキングしないように注意）。

### dataselector / Pickup

- `:dataselector` は `LocationSampleSource` インターフェースにのみ依存し、Room / `StorageService` を直接知らない構成を維持します。
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)` は半開区間 `[from, to)` を表します。

- `SelectorRepository` のポリシー:
  - `intervalSec == null` の場合は「ダイレクト抽出」（グリッド無し）。単純な間引き + 並び替えのみを行います。
  - `intervalSec != null` の場合はグリッド吸着モード:
    - グリッド刻み `T = intervalSec * 1000L` を使い、`±T/2` 窓で代表サンプルを 1 件だけ選びます（同差なら過去側優先）。
    - `SortOrder.NewestFirst` のとき:
      - グリッドは `buildTargetsFromEnd(from, to, T)` を用いて **To 基準**（endInclusive）で生成し、From より前のグリッドは作りません。
      - 最後に `slots.asReversed()` で新しい方から表示します。
    - `SortOrder.OldestFirst` のとき:
      - グリッドは `buildTargetsFromStart(from, to, T)` を用いて **From 基準**（startInclusive）で生成し、To を超えるグリッドは作りません。
      - 昇順（古い→新しい）のまま表示します。

- `SelectorPrefs` は Pickup 条件の永続化用 DataStore です。`SelectorCondition` と同じ単位（from/to はミリ秒、`intervalSec` は秒）で保持します。

- UI (`:app`) からは `SelectorUseCases.buildSelectedSlots(context)` 経由で dataselector を利用します。
  - `PickupScreen` ではこの UseCase を使い、DB/DAO の型を直接知らずに Pickup 結果を取得します。

### GeoLocationService / Dead Reckoning

- 実際の位置取得は `:core` の `GeoLocationService` が担い、FGS（Foreground Service）として動作します。
  - GNSS: `FusedLocationProviderClient` と `LocationRequest` を用いて取得。
  - IMU/DR: `HeadingSensor`, `GnssStatusSampler`, `DeadReckoning` を利用。
  - 設定値: `SettingsRepository.intervalSecFlow` / `drIntervalSecFlow` を購読して間隔を動的に変更。

- Dead Reckoning（`:deadreckoning`）の API:
  - 公開インターフェース: `DeadReckoning`, `GpsFix`, `PredictedPoint`（パッケージ `...deadreckoning.api`）。
  - 設定値: `DeadReckoningConfig`
    - `staticAccelVarThreshold`, `staticGyroVarThreshold`, `processNoisePos`, `velocityGain`, `windowSize` などをまとめたパラメータオブジェクト。
  - ファクトリ: `DeadReckoningFactory.create(context, config = DeadReckoningConfig())`
    - 利用側は実装クラスを意識せずに Dead Reckoning を生成できます。
  - 実装 (`DeadReckoningImpl`) とエンジン (`DeadReckoningEngine`, `SensorAdapter`, `DrState`, `DrUncertainty`) は internal で、今後差し替え可能な内部実装です。

- `GeoLocationService` では:
  - `DeadReckoningFactory.create(applicationContext)` で DR インスタンスを生成し、`start()` / `stop()` を連携させています。
  - DR サンプルの挿入ポリシーや、GNSS サンプルとのロック制御などは今後も `DeadReckoning` API の外側で扱います。

### 設定値の扱い（SettingsRepository）

- サンプリング間隔や DR 間隔は、`storageservice.prefs.SettingsRepository` で管理します。
  - `intervalSecFlow(context)` / `drIntervalSecFlow(context)` が単位「秒」の Flow を返し、既定値・下限値もここで吸収します。
  - 旧コード互換のため `currentIntervalMs(context)` / `currentDrIntervalSec(context)` も提供されていますが、新規コードでは Flow ベースを優先してください。

---

## 認証・Drive 連携の方針

### GoogleDriveTokenProvider と実装

- Drive 連携で使用する認証インターフェースは `GoogleDriveTokenProvider` です。
  - 新規コードは基本的に `CredentialManagerTokenProvider`（`:auth-credentialmanager`）か `AppAuthTokenProvider`（`:auth-appauth`）を実装として使います。
  - レガシーな `GoogleAuthRepository`（GoogleAuthUtil ベース）は後方互換用であり、新しい機能には使わないでください。

- `GoogleDriveTokenProvider` 実装の必須ポリシー:
  - 返すトークン文字列には `"Bearer "` プレフィックスを含めないこと（Authorization ヘッダー側で付与）。
  - 通常の失敗（ネットワーク・未サインイン・同意不足など）では **例外を投げず**、「ログ出力 + `null` 戻り値」とする。
  - UI を伴うフローが必要な場合も、プロバイダ側では UI を開始せず、`null` を返してアプリ側（Activity / Compose 層）に判断を委ねる。

### Credential Manager 認証

- `CredentialManagerAuth` は `CredentialManagerTokenProvider` のシングルトンラッパです。
  - `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` を `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID` 経由で受け取り、Google Identity のサーバーサイド用クライアント ID として扱います。
  - これは **Web アプリケーション / サーバークライアント** 用の ID であり、AppAuth とは用途が異なります。

### AppAuth 認証（AppAuthTokenProvider / AppAuthAuth）

- `AppAuthTokenProvider` は `:auth-appauth` モジュールの `GoogleDriveTokenProvider` 実装です。

- `AppAuthAuth` はそのシングルトンラッパで、次のように初期化します。
  - `clientId = BuildConfig.APPAUTH_CLIENT_ID`
  - `redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"`

- `APPAUTH_CLIENT_ID` は `secrets.properties` の `APPAUTH_CLIENT_ID` から `secrets-gradle-plugin` によって生成されます。

#### Cloud Console 側の前提

- AppAuth 用クライアント ID は **インストールアプリ（Android / その他インストールアプリ）** として作成します。
  - Credential Manager 用の `CREDENTIAL_MANAGER_SERVER_CLIENT_ID`（Web アプリケーション / サーバー クライアント）は流用しないこと。

- AppAuth 用クライアントでは、次の設定が必須です。
  - カスタム URI スキームとして `com.mapconductor.plugin.provider.geolocation` を許可する。
  - リダイレクト URI として  
    `com.mapconductor.plugin.provider.geolocation:/oauth2redirect`  
    を登録する。

#### AppAuthSignInActivity

- `AppAuthSignInActivity` は AppAuth のサインインフローを開始し、結果を受け取るための透明な Activity です。
  - `buildAuthorizationIntent()` でブラウザ / Custom Tab を開き、コールバックで戻った Intent を `handleAuthorizationResponse()` に渡します。

- Manifest では `android:exported="true"` とし、次の Intent Filter を持ちます。
  - scheme: `com.mapconductor.plugin.provider.geolocation`
  - path: `/oauth2redirect`

### DriveTokenProviderRegistry / バックグラウンド

- バックグラウンド（`MidnightExportWorker` など）から Drive にアップロードする場合:
  - UI を伴うフローは行わず、`GoogleDriveTokenProvider.getAccessToken()` が `null` を返したら「認証不足」として扱います。
  - Worker 側では、ZIP は削除するが Room のデータは削除せず、`ExportedDay.lastError` にメッセージを残すポリシーとします。

- バックグラウンド用プロバイダは `DriveTokenProviderRegistry.registerBackgroundProvider(...)` で登録し、バックグラウンドのアップロード処理は必ずこのレジストリ経由で取得する構成を守ってください。

- `DriveTokenProviderRegistry` はアプリプロセス内のシングルトン扱いです。
  - `App` クラスの `onCreate()` で `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))` を呼び出しておきます。

### Drive 設定永続化（AppPrefs / DrivePrefsRepository）

- Drive 関連設定は次の 2 系統で永続化されています。
  - `core.prefs.AppPrefs` … SharedPreferences ベースの旧来設定。`UploadEngine` / `folderId` を保持し、Worker やレガシー経路との互換用。
  - `datamanager.prefs.DrivePrefsRepository` … DataStore ベースの新しい設定。`folderId` / `resourceKey` / `accountEmail` / `uploadEngine` / `authMethod` / `tokenUpdatedAtMillis` などを Flow で管理。

- 新規コードの方針:
  - UI / UseCase から Drive 設定を読む場合は `DrivePrefsRepository` を優先し、`AppPrefs` は「Worker などの互換レイヤ」としてのみ利用します。
  - `DriveSettingsViewModel.validateFolder()` のように、設定確定時に `AppPrefs.saveFolderId` / `saveEngine` にも反映し、旧経路と設定を共有します。

---

## WorkManager / MidnightExportWorker

- `MidnightExportWorker` は「前日以前のバックログを処理する」責務を持ちます。
  - `ZoneId.of("Asia/Tokyo")` を基準に日付を計算し、[0:00, 24:00) のミリ秒区間で 1 日分のレコードを扱います。
  - 初回実行時には、過去 365 日分の `ExportedDay` レコードを `StorageService.ensureExportedDay` でシードします。
  - `StorageService.getLocationsBetween` で 1 日ぶんの `LocationSample` を取得し、`GeoJsonExporter.exportToDownloads` で GeoJSON + ZIP に変換します。

- アップロードと削除のポリシー:
  - ローカル ZIP の出力が成功した時点で `markExportedLocal` を呼びます（空日でも成功扱い）。
  - Drive アップロードは設定 (`AppPrefs.snapshot`) を確認し、エンジンとフォルダ ID が揃っている場合のみ実行します。
  - 成功時には `markUploaded`、HTTP エラーや認証不足などの失敗時には `markExportError` で `lastError` を残します。
  - ZIP ファイルは、成功・失敗を問わず削除します（ローカルストレージひっ迫防止）。
  - アップロードが成功し、かつ 1 日のレコードが存在する場合のみ `StorageService.deleteLocations` によりその日のレコードを削除します。

---

## UI / Compose 方針（:app）

- UI 層は Jetpack Compose を用い、次のパターンを基本とします。
  - 画面単位の Composable: `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen` など。
  - `ViewModel` は `viewModel()` または `AndroidViewModel` を利用し、`viewModelScope` で非同期処理を行う。
  - 状態は StateFlow / `uiState` を経由して Compose に渡す。

- `App` / `MainActivity` / `AppRoot`:
  - アプリケーションクラス `App` では、`DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))` を呼び出し、バックグラウンド用の Drive トークンプロバイダを登録します。
  - 同じく `App` の `onCreate()` で `MidnightExportScheduler.scheduleNext(this)` を呼び出し、日次エクスポート Worker の起動をスケジュールします。
  - `MainActivity` では、権限リクエストを `ActivityResultContracts.RequestMultiplePermissions` で行い、許可後に `GeoLocationService` を開始します。
  - 画面遷移は 2 段構成の `NavHost` で行います。
    - Activity 直下の `NavHost` … `"home"`, `"drive_settings"` を持ち、AppBar の Drive メニューから Drive 設定画面へ遷移します。
    - `AppRoot` 内の `NavHost` … `"home"`, `"pickup"` を持ち、Home 画面と Pickup 画面を切り替えます（AppBar の Pickup ボタン経由）。
  - AppBar から Drive 設定画面・Pickup 画面への遷移を提供し、Start/Stop トグルは `ServiceToggleAction` にまとめます。

---

## テスト・セキュリティ・その他

- ユニットテストは `src/test/java`、計測テスト / Compose UI テストは `src/androidTest/java` に配置します。
  - Drive 連携部分は `GoogleDriveTokenProvider` / HTTP クライアントをモック化してテストしてください。

- 機密ファイル（`local.properties`・`secrets.properties`・`google-services.json` など）はコミット禁止とし、テンプレート + ローカルファイルで管理します。

- Google 認証や Drive 連携の挙動を変更する場合は、README 系（英/日/西）に記載している OAuth スコープやリダイレクト URI が Cloud Console 側の設定と一致していることを確認してください。

- AppAuth と Credential Manager では **別々のクライアント ID** を使います。
  - Credential Manager 用 … `CREDENTIAL_MANAGER_SERVER_CLIENT_ID`（Web/サーバー クライアント）。
  - AppAuth 用 … `APPAUTH_CLIENT_ID`（インストールアプリ + カスタム URI スキーム）。
  - これらを混在させると `invalid_request`（Custom URI scheme is not enabled / not allowed）エラーの原因になるため注意してください。

---

## Public API surface (library)

- `:storageservice`:
  - `StorageService`, `LocationSample`, `ExportedDay`, `SettingsRepository`
- `:dataselector`:
  - `SelectorCondition`, `SortOrder`, `SelectedSlot`
  - `LocationSampleSource`, `SelectorRepository`, `BuildSelectedSlots`, `SelectorPrefs`
- `:datamanager`:
  - Auth and tokens: `GoogleDriveTokenProvider`, `DriveTokenProviderRegistry`
  - Drive settings: `DrivePrefsRepository`
  - Drive API: `DriveApiClient`, `DriveFolderId`, `UploadResult`
  - Upload and export: `Uploader`, `UploaderFactory`, `GeoJsonExporter`
  - Workers: `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`

Non-listed types should remain `internal` or otherwise non-public where possible so that the binary public surface stays small and stable for library consumers.

