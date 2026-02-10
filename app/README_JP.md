# :app (サンプルアプリ / ホストアプリの参照実装)

Gradle モジュール: `:app`

本モジュールは Jetpack Compose のサンプルアプリです。加えて、`GeoLocationProvider` をホストするアプリが各モジュールをどう接続するか(モジュール境界の使い方)を示す参照実装でもあります。

## 何ができるか(効果)

端末でアプリを起動し `Start` を押すと:

- `:core` のフォアグラウンドサービスが開始し、`:gps` の観測を `:storageservice` に保存します。
- (任意) `:deadreckoning` が有効なら、DR 推定点が追加サンプルとして保存されます。
- (任意) IMU 支援 EKF (`ImsEkf`) が有効なら、補正サンプルが `gps_corrected` として保存されます。
- `:datamanager` により、夜間バックアップやリアルタイムアップロードを設定できます。

## このアプリでのモジュール境界の配線

本モジュールは "UI/Android コンポーネント" と "ライブラリモジュール" の境界です。

- `:core` (`GeoLocationService`):
  - `AndroidManifest.xml` で service/権限を宣言
  - UI から開始/停止
  - `SettingsRepository` (DataStore) にインターバル等を保存し、サービス側が Flow を購読して反映
- `:datamanager` (export/upload/Drive):
  - auth モジュールのトークンプロバイダを `DriveTokenProviderRegistry` に登録
  - `MidnightExportScheduler` / `RealtimeUploadManager` を起動
  - `DrivePrefsRepository` / `UploadPrefsRepository` を UI から更新
- `:storageservice` (Room):
  - DAO に触れず `StorageService` 経由で読み書き

## 事前準備

- Android Studio (または Gradle) + JDK 17
- リポジトリ直下に `secrets.properties` (コミットしない)
  - `local.default.properties` をテンプレートとして利用します
- (Map 画面を使う場合) Google Maps API key
  - `AndroidManifest.xml` は `${GOOGLE_MAPS_API_KEY}` を参照します

## ビルドと実行

リポジトリ直下で:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## 使い方(操作 -> 結果)

1. `Start` をタップ
   - 結果: `GeoLocationService` が起動し、`LocationSample` が保存され始めます
2. ホーム画面でインターバルを設定し `Save & Apply`
   - 結果: `SettingsRepository` に永続化され、サービスが動的に追従します
3. `Map` を開く
   - 結果: `GPS` / `GPS(EKF)` / `Dead Reckoning` の軌跡やカウントを可視化できます
4. `Drive` を開いてサインイン/フォルダ設定
   - 結果: バックグラウンド処理が Drive にアップロードできる状態になります
5. `Upload` を開いて夜間/リアルタイムを有効化
   - 結果: `:datamanager` がスケジュール/監視を開始します

## コードの参照先(境界の例)

- 起動時の配線(トークンプロバイダ登録、スケジューラ開始、任意の補正インストール):
  - `app/src/main/java/com/mapconductor/plugin/provider/geolocation/App.kt`
- service と権限の宣言:
  - `app/src/main/AndroidManifest.xml`
- ホーム画面(設定の書き込み + 履歴の表示):
  - `app/src/main/java/com/mapconductor/plugin/provider/geolocation/ui/main/GeoLocationProviderScreen.kt`
- Drive/Upload の設定(Repo の更新 + ワーカー起動):
  - `app/src/main/java/com/mapconductor/plugin/provider/geolocation/ui/settings/DriveSettingsScreen.kt`
  - `app/src/main/java/com/mapconductor/plugin/provider/geolocation/ui/settings/UploadSettingsScreen.kt`

## モジュール境界の変更例(書き換え -> 効果)

### 背景アップロードで使う認証方式を変える

変えること:

- `App.kt` で `GoogleDriveTokenProvider` を選び、登録します:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

効果:

- `:datamanager` の夜間/リアルタイム処理が、その provider からアクセストークンを取得します。
- provider が `null` を返す場合は安全にスキップされます(未認可扱い)。

### GPS EKF 補正を有効/無効にする

変えること:

- 起動時に EKF をインストールし、設定を保存します(詳細は `:core` の README を参照)。

効果:

- 有効時は `provider="gps_corrected"` のサンプルが追加で保存され、Map 画面では `GPS(EKF)` として表示されます。

### export/upload を動かす/止める

変えること:

- 起動時に次を呼ぶ/呼ばないで制御します:
  - `MidnightExportScheduler.scheduleNext(context)`
  - `RealtimeUploadManager.start(context)`

効果:

- 各エントリポイントが起動され、かつ設定が許可している場合のみバックグラウンド処理が動きます。

## 注意

- 本モジュールは参考実装であり、安定したライブラリ API を提供する目的ではありません。
- `secrets.properties` 等の秘匿情報はコミットしないでください。
