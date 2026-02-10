# :deadreckoning (IMU Dead Reckoning)

Gradle モジュール: `:deadreckoning`

本モジュールは IMU を用いた Dead Reckoning エンジンを提供し、GPS fix 間の中間位置を推定できます。公開 API は小さく保たれており、`DeadReckoningFactory` 経由で利用します。

## モジュール境界の契約(入力 -> 出力)

入力:

- 端末に IMU センサ(加速度/ジャイロ)が存在すること
- GPS fix を `submitGpsFix(...)` で投入すること
- 予測したい時間範囲を `predict(fromMillis, toMillis)` で指定すること

出力:

- 指定範囲の `PredictedPoint` リスト
- 静止推定のフラグ `isLikelyStatic()`

## 公開 API

パッケージ: `com.mapconductor.plugin.provider.geolocation.deadreckoning.api`

- `DeadReckoning`: start/stop、`submitGpsFix(...)`、`predict(fromMillis, toMillis)`
- `DeadReckoningConfig`: チューニング用パラメータ
- `GpsFix`: GPS からのアンカー入力
- `PredictedPoint`: 出力点
- `DeadReckoningFactory`: `DeadReckoning` の生成

対象:

- `deadreckoning/src/main/java/com/mapconductor/plugin/provider/deadreckoning/api/DeadReckoning.kt`
- `deadreckoning/src/main/java/com/mapconductor/plugin/provider/deadreckoning/api/DeadReckoningConfig.kt`
- `deadreckoning/src/main/java/com/mapconductor/plugin/provider/deadreckoning/api/DeadReckoningFactory.kt`

## 典型的な使い方

```kotlin
val dr = DeadReckoningFactory.create(appContext, DeadReckoningConfig())
dr.start()

dr.submitGpsFix(
  GpsFix(
    timestampMillis = t,
    lat = lat,
    lon = lon,
    accuracyM = accuracy,
    speedMps = speed
  )
)

val points: List<PredictedPoint> = dr.predict(fromMillis = t0, toMillis = t1)
```

不要になったら停止:

```kotlin
dr.stop()
```

## モジュール境界の使い方(詳細: 書き方 -> 結果)

### パターン: realtime 予測 ticker (DR Prediction)

書くこと:

- GPS fix をアンカーとして `submitGpsFix(...)` で投入します。
- タイマーで定期的に `predict(lastFixMillis, nowMillis)` を呼びます。

得られるもの:

- 中間点 `PredictedPoint` のリストが得られ、より滑らかな経路として保存/可視化できます。
- `:core` 統合では ticker により `LocationSample(provider="dead_reckoning")` が追加で挿入される挙動に対応します。

### パターン: GPS fix 間の backfill (DR Completion)

書くこと:

- 新しい GPS fix が来るたびに、1 回だけ `predict(prevFixMillis, currentFixMillis)` を呼びます。

得られるもの:

- 2 つの GPS アンカー間を密に埋めた点列が得られます。
- `:core` 統合では GPS-GPS 間の `dead_reckoning` サンプル backfill に対応します。

### パターン: 静止時のクランプに使う

書くこと:

- `dr.isLikelyStatic()` を参照し、静止時の描画/更新を抑制します。

得られるもの:

- 静止時のジッタを減らすための簡易ヒントとして使えます。

### パターン: `DeadReckoningConfig` でチューニング

書くこと:

- まずデフォルトで開始し、必要なパラメータだけ調整します:
  - `velocityGain`: GPS 速度を内部推定にどれだけ反映するか
  - `maxStepSpeedMps`: スパイク抑制のための速度上限
  - `staticAccelVarThreshold` / `staticGyroVarThreshold`: 静止判定の感度

得られるもの:

- 端末のセンサ品質やユースケースに合わせた安定出力が得られます。

## 注意

- 最初の GPS fix が投入されるまでは絶対位置の基準が無いため、`predict` が空になる場合があります。
- `isLikelyStatic()` はエンジン内部の静止判定を公開します。呼び出し側のクランプ等に利用できます。

## 関連モジュール

- `../core/README.md` (サービス統合とモード)
