# Repository Guidelines (日本語版)

GeoLocationProvider リポジトリで作業する際の **共通方針・コーディング規約・モジュール間の責務分担** をまとめたドキュメントです。  
コードを変更する前に一度読んでいただき、ここに書かれた方針に沿って実装してください。

---

## プロジェクト構成とモジュール

- ルート Gradle プロジェクト `GeoLocationProvider` は次のモジュールで構成されています。
  - `:app` – Compose ベースのサンプル UI アプリ（履歴、Pickup、Drive 設定、手動バックアップなど）。
  - `:core` – 位置取得サービス (`GeoLocationService`) とセンサー関連ロジック。永続化は `:storageservice` に委譲。
  - `:storageservice` – Room `AppDatabase` / DAO と `StorageService` ファサード。位置ログとエクスポート状態を一括管理。
  - `:dataselector` – Pickup 向けの選択ロジック。`LocationSample` を条件でフィルタし、代表サンプル行 (`SelectedSlot`) を構築。
  - `:datamanager` – GeoJSON エクスポート、ZIP 圧縮、`MidnightExportWorker` / `MidnightExportScheduler`、Google Drive 連携。
  - `:deadreckoning` – Dead Reckoning エンジンと API。`GeoLocationService` から利用。
  - `:auth-appauth` – AppAuth ベースの `GoogleDriveTokenProvider` 実装を提供するライブラリモジュール。
  - `:auth-credentialmanager` – Credential Manager + Identity ベースの `GoogleDriveTokenProvider` 実装を提供するライブラリモジュール。

- 依存関係のおおまかな向き:
  - `:app` → `:core`, `:dataselector`, `:datamanager`, `:storageservice`, 認証系モジュール
  - `:core` → `:storageservice`, `:deadreckoning`
  - `:datamanager` → `:storageservice`, Drive 連携クラス
  - `:dataselector` → `LocationSampleSource` 抽象のみ（実装は `:app` 側で `StorageService` をラップ）

- 本番コードは各モジュールの `src/main/java`, `src/main/kotlin`, `src/main/res` に配置します。  
  ビルド成果物はモジュール配下の `build/` 以下に生成されます。

- 開発マシン固有の設定（Android SDK のパスなど）はルートの `local.properties` に記述します。  
  通常は Android Studio が自動生成しますが、存在しない場合は手動で作成してください。

- 認証や機密情報は `secrets.properties` に保存し、このファイルは **Git にコミットしない** でください。  
  テンプレートとして `local.default.properties` が用意されているので、コピーして実際の値に置き換えます。

---

## ビルド・テスト・開発用コマンド

- 代表的な Gradle コマンド（ルートディレクトリで実行）:
  - `./gradlew :app:assembleDebug` – サンプルアプリの Debug APK をビルド。
  - `./gradlew :core:assemble` / `./gradlew :storageservice:assemble` – ライブラリモジュールをビルド。
  - `./gradlew lint` – Android / Kotlin の静的解析を実行。
  - `./gradlew test` / `./gradlew :app:connectedAndroidTest` – ユニットテスト / UI・計測テストを実行。

- 日常開発では Android Studio からのビルド・実行を基本とし、Gradle CLI は CI や一括検証で使用します。

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
- 未使用の import や死んだコードを見つけた場合は、可能な範囲で削除します。
- KDoc / コメントでは、次の観点を明示することを推奨します。
  - 役割・責務
  - 設計方針
  - 想定する利用方法
  - 契約（呼び出し側が期待できること / できないこと）

### コメントとエンコーディングの方針

- 本番コード（Kotlin / Java / XML / Gradle スクリプトなど）は、**ASCII のみ**で記述します。  
  コード・コメント・文字列リテラルにはマルチバイト文字を使用しないでください。
- 日本語 / スペイン語などの多言語テキストは、`README_JA.md` / `README_ES.md` をはじめとする `*.md` ドキュメントにのみ含めることを許容します。
- 公開 API や主要クラスには KDoc（`/** ... */`）を用い、内部実装の補足にはシンプルな `// ...` 行コメントを使う方針とします。
- セクション区切りのコメントは、`// ---- Section ----` や `// ------------------------` のような簡潔なスタイルに統一し、装飾的な枠線は避けます。
- モジュールをまたいで機能を移動・リファクタリングする際は、**コメントの粒度と書きぶりが大きくブレないように**そろえることを意識してください。

---

## レイヤリングと責務分担

### StorageService / Room アクセス

- Room の `AppDatabase` / DAO へのアクセスは、基本的に `StorageService` 経由で行います。
  - 例外は `:storageservice` モジュール内部のみ（DAO 実装と `AppDatabase` 本体）。
  - `:app`, `:core`, `:datamanager`, `:dataselector` から DAO 型を直接 import しないでください。

- `StorageService` の主な契約:
  - `latestFlow(ctx, limit)` – 最新 `limit` 件の `LocationSample` を新しい順の Flow で返す。
  - `getAllLocations(ctx)` – すべての位置ログを `timeMillis` 昇順で返す（小規模データ向け）。
  - `getLocationsBetween(ctx, from, to, softLimit)` – `[from, to)` の半開区間で `timeMillis` 昇順のデータを返す。大規模データではこの API を使用。
  - `insertLocation(ctx, sample)` – 1 件挿入し、前後の件数を `DB/TRACE` タグでログに出す。
  - `deleteLocations(ctx, items)` – 空リストなら何もしない。例外はそのまま呼び出し側へ伝播。
  - `ensureExportedDay` / `oldestNotUploadedDay` / `markExportedLocal` / `markUploaded` / `markExportError` – 日単位のエクスポート状態を管理。

- DB アクセスは `Dispatchers.IO` 上で行う前提です。  
  呼び出し側でさらに Dispatcher を切り替える必要はありませんが、UI スレッドをブロックしないよう注意してください。

### dataselector / Pickup

- `:dataselector` モジュールは `LocationSampleSource` 抽象のみに依存し、Room や `StorageService` そのものは知りません。
  - `LocationSampleSource.findBetween(fromInclusive, toExclusive)` は `[from, to)` の半開区間で昇順取得する契約です。

- `SelectorRepository` のポリシー（簡略版）:
  - `intervalSec == null`: グリッド無しの「そのまま抽出」。簡易な間引きとソートのみ。
  - `intervalSec != null`: グリッド吸着モード。  
    - `T = intervalSec * 1000L` とし、各グリッドごとに `±T/2` の窓から 1 サンプルだけ選ぶ（複数ある場合はより過去側を優先）。  
    - `SortOrder.NewestFirst`: 終了時刻 (`to`) を基準に `buildTargetsFromEnd` でグリッドを生成し、最終的に `slots.asReversed()` で新しい順に並べ替える。  
    - `SortOrder.OldestFirst`: 開始時刻 (`from`) を基準に `buildTargetsFromStart` でグリッドを生成し、昇順のまま返す。

- `SelectorPrefs` は Pickup 条件を DataStore で永続化します（`SelectorCondition` と同じ単位 / 意味）。
- UI (`:app`) からは `SelectorUseCases.buildSelectedSlots(context)` を通して dataselector を利用し、Pickup 画面は DB/DAO 型を知らなくて済むようにします。

### GeoLocationService / Dead Reckoning

- 実際の位置取得は `:core` の `GeoLocationService` が行い、フォアグラウンドサービスとして動作します。
  - GNSS: `FusedLocationProviderClient` + `LocationRequest`
  - IMU / DR: `HeadingSensor`, `GnssStatusSampler`, `DeadReckoning`
  - 設定: `SettingsRepository.intervalSecFlow` / `drIntervalSecFlow` を購読してサンプリング間隔を動的に更新

- Dead Reckoning API (`:deadreckoning`):
  - 公開 API: `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`
  - `DeadReckoningConfig` は静止判定しきい値・位置ノイズモデルなど DR エンジンのパラメータをまとめた設定オブジェクト。
  - `DeadReckoningFactory.create(context, config)` で実装詳細に依存せず `DeadReckoning` を生成。
  - 実装 (`DeadReckoningImpl`) とエンジン (`DeadReckoningEngine`, `SensorAdapter`, `DrState`, `DrUncertainty`) は internal とし、将来差し替え可能な設計。

- `GeoLocationService` 側のポリシー:
  - `DeadReckoningFactory.create(applicationContext)` で DR インスタンスを生成し、`start()` / `stop()` に連動。
  - DR サンプルの挿入タイミングや GNSS サンプルとの協調ロジックは、`DeadReckoning` API の外側で扱う（サービス側の責務）。

### SettingsRepository による設定管理

- `storageservice.prefs.SettingsRepository` がサンプリング間隔などを管理します。
  - `intervalSecFlow(context)` / `drIntervalSecFlow(context)` – 秒単位で Flow を返す（デフォルトと最小値を含む）。
  - `currentIntervalMs(context)` / `currentDrIntervalSec(context)` – 旧実装との互換用の同期 API（新コードでは Flow を優先）。

---

## 認証と Drive 連携

### GoogleDriveTokenProvider と実装

- Drive 連携のための認証インターフェースが `GoogleDriveTokenProvider` です。
  - 新規コードでは `:auth-credentialmanager` の `CredentialManagerTokenProvider` または `:auth-appauth` の `AppAuthTokenProvider` を使うことを推奨。
  - 旧来の `GoogleAuthRepository`（GoogleAuthUtil ベース）は後方互換用に残していますが、新規コードでは使用しません。

- ポリシー:
  - 戻り値のトークンは `"Bearer "` を含めず、生のアクセストークン文字列のみを返す。
  - 正常な失敗（ネットワークエラー・未サインイン・同意不足など）は例外ではなく `null` で表現する（ログ出力は可）。
  - UI フローが必要な場合でも、プロバイダ側から直接 UI を起動せず、`null` を返してアプリ側に判断を委ねる。

### Credential Manager 認証

- `CredentialManagerTokenProvider` は Credential Manager + Identity ベースの `GoogleDriveTokenProvider` 実装です。
  - `BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID` を `CREDENTIAL_MANAGER_SERVER_CLIENT_ID` として受け取り、Google Identity の Web / サーバ用クライアント ID として扱います。
  - AppAuth のクライアント ID とは別のものを使う必要があります。

### AppAuth 認証

- `AppAuthTokenProvider` は `:auth-appauth` モジュール内の `GoogleDriveTokenProvider` 実装です。
  - `clientId = BuildConfig.APPAUTH_CLIENT_ID`
  - `redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"`
  - `APPAUTH_CLIENT_ID` は `secrets.properties` の `APPAUTH_CLIENT_ID` から `secrets-gradle-plugin` 経由で生成されます。

### DriveTokenProviderRegistry / バックグラウンド

- バックグラウンド処理（`MidnightExportWorker` など）では UI を起動せず、`DriveTokenProviderRegistry` に登録されたプロバイダを経由してトークンを取得します。
  - `DriveTokenProviderRegistry.registerBackgroundProvider(...)` で Application 起動時に登録する（`App.onCreate()` で `CredentialManagerAuth.get(this)` を登録するのがサンプル構成）。
  - `getBackgroundProvider()` が `null` を返した場合は「未認証」とみなし、ワーカー側で ZIP の削除・エラー記録のみを行います（レコード削除はしない）。

---

## WorkManager / MidnightExportWorker

- `MidnightExportWorker` は「前日までのバックログ」を処理します。
  - タイムゾーン `Asia/Tokyo` で日付を計算し、1 日分を `[0:00, 24:00)` のミリ秒レンジとして扱います。
  - 初回実行時に過去 365 日分の `ExportedDay` を `StorageService.ensureExportedDay` でシード。
  - 各日について `StorageService.getLocationsBetween` で `LocationSample` を取得し、`GeoJsonExporter.exportToDownloads` で GeoJSON + ZIP に変換。

- アップロードと削除ポリシー:
  - ローカル ZIP が正常に出力できた時点で `markExportedLocal` を呼ぶ（その日が空でも成功扱い）。
  - Drive アップロードは `AppPrefs.snapshot` の設定を確認し、エンジンとフォルダ ID が揃っている場合のみ行う。
  - 成功したら `markUploaded`、HTTP エラーや認証エラー時は `markExportError` で `lastError` を保存。
  - ZIP ファイルは成功 / 失敗にかかわらず必ず削除してストレージ肥大化を防ぐ。
  - アップロードが成功し、その日にレコードが存在する場合のみ `StorageService.deleteLocations` で当日のレコードを削除。

---

## UI / Compose ガイドライン (`:app`)

- UI 層は Jetpack Compose を使用し、次のパターンに従います。
  - 画面レベルの Composable: `GeoLocationProviderScreen`, `PickupScreen`, `DriveSettingsScreen` など
  - ViewModel は `viewModel()` / `AndroidViewModel` で生成し、`viewModelScope` で非同期処理を行う
  - 状態は `StateFlow` / `uiState` として公開し、Compose に渡す

- `App` / `MainActivity` / `AppRoot`:
  - `App.onCreate()` で  
    `DriveTokenProviderRegistry.registerBackgroundProvider(CredentialManagerAuth.get(this))` を呼び、バックグラウンド用 Drive トークンプロバイダを登録。
  - 同じく `App.onCreate()` で `MidnightExportScheduler.scheduleNext(this)` を呼び、日次エクスポート Worker をスケジュール。
  - `MainActivity` では `ActivityResultContracts.RequestMultiplePermissions` で権限をリクエストし、許可後に `GeoLocationService` を起動。
  - ナビゲーションは 2 段階の `NavHost` 構成:
    - Activity 直下の `NavHost`: `"home"`, `"drive_settings"`。AppBar の Drive メニューから Drive 設定画面へ遷移。
    - `AppRoot` 内の `NavHost`: `"home"`, `"pickup"`。AppBar の Pickup ボタンで Home / Pickup を切り替え。
  - AppBar は Drive 設定画面・Pickup 画面へのナビゲーションを提供し、サービスの Start/Stop トグルは `ServiceToggleAction` にカプセル化。

---

## テスト・セキュリティ・その他

- ユニットテストは `src/test/java`、計測 / Compose UI テストは `src/androidTest/java` に配置。
  - Drive 連携のテストでは `GoogleDriveTokenProvider` と HTTP クライアントをモックしてテストします。

- `local.properties`, `secrets.properties`, `google-services.json` などの機密ファイルは Git にコミットしないでください。  
  テンプレート + ローカルファイルで運用します。

- Google 認証や Drive 連携の挙動を変更した場合は、README（EN/JA/ES）に記載された OAuth スコープやリダイレクト URI が  
  Cloud Console の設定と一致していることを確認してください。

- AppAuth と Credential Manager では **別々のクライアント ID** を使用します。
  - Credential Manager – `CREDENTIAL_MANAGER_SERVER_CLIENT_ID`（Web / サーバクライアント）
  - AppAuth – `APPAUTH_CLIENT_ID`（インストールアプリ + カスタム URI スキーム）
  - これらを混在させると `invalid_request`（例: "Custom URI scheme is not enabled / not allowed"）などのエラー原因になります。

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
  - アップロード / エクスポート: `Uploader`, `UploaderFactory`, `GeoJsonExporter`
  - Worker: `MidnightExportWorker`, `MidnightExportScheduler`
- `:deadreckoning`:
  - `DeadReckoning`, `GpsFix`, `PredictedPoint`, `DeadReckoningConfig`, `DeadReckoningFactory`

ここに列挙されていない型は、可能な限り `internal` などで非公開のままにし、  
ライブラリとしての公開バイナリ API を小さく安定したものに保ってください。

