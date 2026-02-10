# :core (フォアグラウンド測位サービス)

Gradle モジュール: `:core`

本モジュールはフォアグラウンドサービス `GeoLocationService` を提供します。これはシステムの "実行時オーケストレータ" であり、GPS 観測を受け取り、追加情報(方位、GNSS 品質、任意の補正)を付与しつつ、`LocationSample` を `:storageservice` に保存します。必要に応じて `:deadreckoning` による Dead Reckoning (DR) も統合します。

## 主な責務

- フォアグラウンド追跡のライフサイクル(開始/停止、通知チャネル)
- `:gps` の `GpsLocationEngine` からの観測処理
- (任意) 補正エンジンの差し替えポイント(`GpsCorrectionEngineRegistry`)
- (任意) Dead Reckoning の統合(`DeadReckoningFactory`、予測/補間)
- `StorageService` 経由での永続化(DAO を直接触らない)

## エントリポイント

- `GeoLocationService`: `core/src/main/java/com/mapconductor/plugin/provider/geolocation/service/GeoLocationService.kt`

## モジュール境界の契約(入力 -> 出力)

### 入力(ホストアプリ側が用意するもの)

- Android コンポーネントの配線:
  - manifest に service を宣言
  - UI などフォアグラウンドから開始/停止
- `:storageservice` の設定 Flow:
  - GPS interval (`SettingsRepository.intervalSecFlow`)
  - DR interval (`SettingsRepository.drIntervalSecFlow`)
  - DR mode (`SettingsRepository.drModeFlow`)
  - (任意) DR GPS interval (`SettingsRepository.drGpsIntervalSecFlow`)
- (任意) GPS 補正:
  - 補正エンジンをレジストリへ登録(例: `ImsEkf.install(...)`)

### 出力(本モジュールが生成するもの)

- `StorageService.insertLocation(...)` により `LocationSample` を保存します。
  - `provider="gps"` (raw GPS)
  - `provider="gps_corrected"` (補正が有効で raw と差が出た場合)
  - `provider="dead_reckoning"` (DR 有効時)

### 実行中の副作用

- フォアグラウンド通知を維持します。
- センサを開始/停止します(方位センサ、補正が有効なら IMU など)。
- 重複 insert を簡易ガードで抑制します。

## Intent による制御

`GeoLocationService` は Intent の action で制御できます:

- `ACTION_START` / `ACTION_STOP`
- `ACTION_UPDATE_INTERVAL` + `EXTRA_UPDATE_MS`
- `ACTION_UPDATE_DR_INTERVAL` + `EXTRA_DR_INTERVAL_SEC`
- `ACTION_UPDATE_DR_GPS_INTERVAL` + `EXTRA_DR_GPS_INTERVAL_SEC`

正確な定数名は `GeoLocationService.kt` の `companion object` を参照してください。

## アプリへの組み込み

1. 依存関係を追加します:

```kotlin
dependencies {
  implementation(project(":core"))
}
```

2. アプリの Manifest に service を宣言し、targetSdk に応じた権限を追加します。
   - Location: `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
   - Foreground service: `FOREGROUND_SERVICE` と `FOREGROUND_SERVICE_LOCATION` (Android 14+)
   - Notification: `POST_NOTIFICATIONS` (Android 13+)

3. `ACTION_START` で起動します:

```kotlin
val intent = Intent(context, GeoLocationService::class.java).apply {
  action = GeoLocationService.ACTION_START
}
ContextCompat.startForegroundService(context, intent)
```

停止:

```kotlin
val intent = Intent(context, GeoLocationService::class.java).apply {
  action = GeoLocationService.ACTION_STOP
}
context.startService(intent)
```

## レシピ(何をする -> 何が起きる)

### レシピ: Tracking のみ(GPS -> DB)

やること:

- `ACTION_START` でサービスを起動します。

起きること:

- GPS 観測が `LocationSample` に変換され `provider="gps"` で DB に保存されます。

### レシピ: DR を有効化(GPS アンカー -> DR サンプル)

やること:

- `SettingsRepository.setDrIntervalSec(context, sec)` を `> 0` に設定します。

起きること:

- サービスが GPS fix を DR エンジンへ投入します。
- DR mode により:
  - Prediction: ticker で DR 点を `provider="dead_reckoning"` として保存
  - Completion: GPS-GPS 間をバックフィルし `provider="dead_reckoning"` として保存

### レシピ: GPS 補正(EKF)を有効化(補正 -> gps_corrected)

やること:

- `ImsEkf.install(...)` を行い、設定で enabled を true にします。

起きること:

- 各 GPS 観測に対して補正結果が生成されます。
- raw と異なる場合、追加で `provider="gps_corrected"` のサンプルが保存されます。

### レシピ: サービス稼働中に設定を変更

やること:

- UI から `SettingsRepository` に新しい値を書き込みます。

起きること:

- サービス側が Flow を購読しており、GPS 更新間隔や DR の ticker を動的に更新します。

## モジュール境界の使い方(詳細: 書き方 -> 結果)

このセクションは、ホストアプリに何を書くと何が起きるかをもう少し具体的に示します。

### EKF 補正を end-to-end で有効化する

書くこと:

- プロセス起動時に EKF 補正をインストールする。
- `ImsEkfConfigRepository` に `ImsEkfConfig` を保存し、`enabled=true` にする。

```kotlin
// Application.onCreate()
ImsEkf.install(appContext)
ImsEkfConfigRepository.set(
  appContext,
  ImsEkfConfig.defaults(ImsUseCase.WALK).copy(enabled = true)
)
```

起きること:

- サービス実行中に補正エンジンのライフサイクルが開始されます。
- 各 GPS 観測に対して補正後(融合)の観測が生成される場合があります。
- raw と差が出た場合、追加で `LocationSample(provider="gps_corrected")` が挿入されます。
- サンプル UI ではこのストリームを別扱いで可視化できます(アプリ上は `GPS(EKF)` 表示)。

よくある落とし穴:

- インストールだけでは `gps_corrected` は出ません。`enabled=true` が必要です。

### サービスを再起動せずにサンプリングを更新する

書くこと(UI/ViewModel から。これらは `suspend` API):

```kotlin
viewModelScope.launch {
  SettingsRepository.setIntervalSec(context, sec = 1)
  SettingsRepository.setDrIntervalSec(context, sec = 0) // DR 無効
  SettingsRepository.setDrMode(context, mode = DrMode.Prediction)
}
```

起きること:

- 実行中のサービスが `SettingsRepository` の Flow を監視し、以下を更新します:
  - GPS 更新間隔
  - DR ticker の開始/停止
  - DR mode (Prediction / Completion)
- サービス再起動は不要です。

### DR mode と出力の関係(何が増えるか)

書くこと:

- `drIntervalSec = 0` で DR を完全に無効化。
- `drIntervalSec > 0` で DR を有効化し、`drMode` で出力形を選ぶ。

起きること:

- `DrMode.Prediction`:
  - おおむね `drIntervalSec` 秒ごとに追加の realtime 点を `provider="dead_reckoning"` で挿入
- `DrMode.Completion`:
  - ticker は動かさず、GPS fix 間の点を backfill して `provider="dead_reckoning"` で挿入

### Provider ごとに明示的に消費する(GPS / 補正 / DR)

書くこと:

- UI/エクスポート/診断で特定の系列だけが欲しい場合、`LocationSample.provider` でフィルタします。

```kotlin
StorageService.latestFlow(context, limit = 500).collect { rows ->
  val gps = rows.filter { it.provider == "gps" }
  val ekf = rows.filter { it.provider == "gps_corrected" }
  val dr = rows.filter { it.provider == "dead_reckoning" }
}
```

起きること:

- raw GPS / 補正 GPS / DR を安定して分離できます。
- Map 画面での比較や、系列別のエクスポートが行いやすくなります。

### GPS エンジンを差し替える(上級)

書くこと:

- `:gps` に新しい `GpsLocationEngine` 実装を追加(例: Google Play Services ベース)。
- `GeoLocationService` が生成するエンジンを `LocationManagerGpsEngine` から差し替える。

起きること:

- 永続化/DR の挙動はそのままに、`GpsObservation` の供給元だけを置き換えられます。

## 関連モジュール

- `../gps/README.md`
- `../storageservice/README.md`
- `../deadreckoning/README.md`
