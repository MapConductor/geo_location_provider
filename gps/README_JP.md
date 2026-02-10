# :gps (GPS 抽象化)

Gradle モジュール: `:gps`

本モジュールは、プラットフォームの位置更新を小さな抽象(API)にまとめ、他モジュールが安定したドメインモデル `GpsObservation` と差し替え可能なエンジン `GpsLocationEngine` を使えるようにします。

## モジュール境界の契約

入力:

- ホスト側が `Context` と `Looper` を渡します(コールバックのスレッド制御)。
- 位置情報権限はホストアプリが付与します。

出力:

- `GpsLocationEngine.Listener` に `GpsObservation` が通知されます。
  - lat/lon/accuracy に加え、取得できる場合は GNSS メタデータ(使用衛星数/総数、CN0 mean)も含みます。

## 公開 API

- `GpsLocationEngine`: start/stop、インターバル更新、listener 設定
- `GpsObservation`: 1 回分の位置観測 + 任意の GNSS メタデータ

対象ファイル:

- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/GpsLocationEngine.kt`
- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/GpsObservation.kt`

## 同梱実装

- `LocationManagerGpsEngine`
  - Android の `LocationManager` と `GnssStatus.Callback` を利用します。
  - Google Play Services が利用できない環境を想定しています。

対象:

- `gps/src/main/java/com/mapconductor/plugin/provider/geolocation/gps/LocationManagerGpsEngine.kt`

## 典型的な使い方

エンジン作成 -> listener 設定 -> start:

```kotlin
val engine = LocationManagerGpsEngine(appContext, Looper.getMainLooper())
engine.setListener(object : GpsLocationEngine.Listener {
  override fun onGpsObservation(observation: GpsObservation) {
    // 観測を処理する
  }
})
engine.updateInterval(5_000L)
engine.start()
```

不要になったら stop:

```kotlin
engine.stop()
engine.setListener(null)
```

## 権限

最低限:

- `android.permission.ACCESS_FINE_LOCATION` (推奨)
- または `android.permission.ACCESS_COARSE_LOCATION`

フォアグラウンドサービスから使う場合は、アプリ側の manifest にフォアグラウンドサービス関連の権限も宣言してください。

## 独自 `GpsLocationEngine` の実装(上級)

別の位置情報プロバイダ(例: Google Play Services)を使いたい場合は、本モジュール内で `GpsLocationEngine` を実装します。

書くこと:

- プラットフォームのコールバックを `GpsObservation` に変換して流す `GpsLocationEngine` 実装。

得られるもの:

- `:core` はプラットフォーム固有の型に依存せず観測を消費できます。
- 永続化や下流ロジックは変えず、観測の供給元だけを差し替えられます。

ひな形:

```kotlin
class MyGpsEngine : GpsLocationEngine {
  private var listener: GpsLocationEngine.Listener? = null

  override fun setListener(listener: GpsLocationEngine.Listener?) {
    this.listener = listener
  }

  override fun updateInterval(intervalMs: Long) {
    // platform の要求間隔を更新
  }

  override fun start() {
    // platform 更新を開始し、emit(...) する
  }

  override fun stop() {
    // platform 更新を停止
  }

  private fun emit(observation: GpsObservation) {
    listener?.onGpsObservation(observation)
  }
}
```

変換のポイント:

- 可能なら単調時間(`elapsedRealtimeNanos`)を使うと、重複抑制や並びの助けになります。
- GNSS メタ情報は任意です。取得できない場合は `null` をセットしてください。

## 補足

- 本モジュールの目的は、GPS 統合をテストしやすく差し替え可能にすることです。
- Android のコールバック型を外に漏らさず、`GpsObservation` を使ってください。
