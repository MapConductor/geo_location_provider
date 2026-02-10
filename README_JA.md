# GeoLocationProvider (日本語概要)

GeoLocationProvider は、位置情報を記録・保存・エクスポート・アップロードするための Android 向けマルチモジュール SDK とサンプルアプリです。

概要:

- 位置サンプルを Room DB に保存
- (任意) Dead Reckoning (DR) サンプルを生成
- GeoJSON / GPX へエクスポート (任意で ZIP 化)
- Google Drive へアップロード (夜間バックアップ + 任意のリアルタイムアップロード)

他言語:

- English: `README.md`
- Spanish: `README_ES.md`

## どこから読むか

- サンプルアプリを動かす: この README の後に `app/README.md`。
- SDK を自作アプリで使う: `core/README.md` と `storageservice/README.md` から。
- Drive への export/upload: `datamanager/README.md` と auth モジュールのどちらか。

## クイックスタート (サンプルアプリ)

前提:

- Android Studio (または Gradle) + JDK 17
- Android SDK
- リポジトリ直下に `secrets.properties` (コミットしない)
  - `local.default.properties` をテンプレートとして利用

ビルドとインストール:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

アプリでの基本フロー:

1. `Start` を押してフォアグラウンド測位サービスを開始
2. GPS/DR の間隔を設定して `Save & Apply`
3. `Map` でプロバイダ別に可視化 (GPS / GPS(EKF) / Dead Reckoning)
4. `Drive` でサインインし、Drive フォルダを設定
5. `Upload` で夜間 / リアルタイムを有効化

## ドキュメントの地図

ルートドキュメント:

- リポジトリのガイドラインとモジュール責務: `AGENTS.md` (併せて `AGENTS_JA.md`, `AGENTS_ES.md`)
- 概要: `README.md` (EN), `README_JA.md` (JA), `README_ES.md` (ES)

モジュール別ドキュメント (モジュール境界の使い方はここが主):

- `app/README.md` (JP: `app/README_JP.md`, ES: `app/README_ES.md`)
- `core/README.md` (JP: `core/README_JP.md`, ES: `core/README_ES.md`)
- `storageservice/README.md` (JP: `storageservice/README_JP.md`, ES: `storageservice/README_ES.md`)
- `gps/README.md` (JP: `gps/README_JP.md`, ES: `gps/README_ES.md`)
- `deadreckoning/README.md` (JP: `deadreckoning/README_JP.md`, ES: `deadreckoning/README_ES.md`)
- `dataselector/README.md` (JP: `dataselector/README_JP.md`, ES: `dataselector/README_ES.md`)
- `datamanager/README.md` (JP: `datamanager/README_JP.md`, ES: `datamanager/README_ES.md`)
- `auth-credentialmanager/README.md` (JP: `auth-credentialmanager/README_JP.md`, ES: `auth-credentialmanager/README_ES.md`)
- `auth-appauth/README.md` (JP: `auth-appauth/README_JP.md`, ES: `auth-appauth/README_ES.md`)

注意: モジュール直下の日本語版は `README_JP.md` ですが、ルートの日本語概要は `README_JA.md` です。

## アーキテクチャ概要

データの流れ:

1. `:gps` が `GpsObservation` を生成 (現状は `LocationManagerGpsEngine`)。
2. `:core` (`GeoLocationService`) が `LocationSample` に変換し、`:storageservice` 経由で保存。
3. `:deadreckoning` を `:core` から利用して DR サンプルも生成可能 (`provider="dead_reckoning"`)。
4. `:datamanager` がエクスポート / アップロード:
   - 夜間 worker: 日別 ZIP を Downloads と Drive へ
   - realtime manager: cache のスナップショットを Drive へ (成功時にアップロード済み DB 行を削除)
5. auth モジュールが `GoogleDriveTokenProvider` を実装し、`DriveTokenProviderRegistry` 経由で登録して利用。

モジュール境界の目安:

- Room DAO を直接触るのは `:storageservice` のみ。
- 他モジュールは `StorageService` と契約 (並び順 / 範囲 / エラー) に従って利用。

## ローカル設定ファイル

- `local.properties` (コミットしない): Android SDK パスとローカル設定
- `secrets.properties` (コミットしない): Secrets Gradle Plugin の値。例:

```properties
CREDENTIAL_MANAGER_SERVER_CLIENT_ID=YOUR_SERVER_CLIENT_ID.apps.googleusercontent.com
APPAUTH_CLIENT_ID=YOUR_APPAUTH_CLIENT_ID.apps.googleusercontent.com
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

## ビルドと検証

```bash
./gradlew test
./gradlew lint
```

## 開発メモ

- 本番ソース (コード) は ASCII のみ。ドキュメントは多言語を許可しています。
- `secrets.properties` や `google-services.json` などの秘匿情報はコミットしないでください。
