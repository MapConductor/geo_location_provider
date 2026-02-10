# :auth-appauth (AppAuth による Google Drive トークン供給)

Gradle モジュール: `:auth-appauth`

本モジュールは、Google Drive アップロード用の `GoogleDriveTokenProvider` を AppAuth で実装します。

## モジュール境界の契約

ホスト側がやること:

- フォアグラウンド Activity で対話的に OAuth フローを実行する
- リダイレクトを受け取って状態(AuthState)を永続化する

得られるもの(効果):

- `GoogleDriveTokenProvider` としてアクセストークンを供給できる
- `:datamanager` のバックグラウンド処理が `DriveTokenProviderRegistry` 経由で利用できる

## 提供するもの

- `AppAuthTokenProvider`: AppAuth を使った OAuth 2.0 (Authorization Code + PKCE) により `GoogleDriveTokenProvider` を実装

対象:

- `auth-appauth/src/main/java/com/mapconductor/plugin/provider/geolocation/auth/appauth/AppAuthTokenProvider.kt`

## 使い方(概要)

1. 依存関係を追加:

```kotlin
dependencies {
  implementation(project(":auth-appauth"))
}
```

2. プロバイダを作成:

```kotlin
val provider = AppAuthTokenProvider(
  context = appContext,
  clientId = BuildConfig.APPAUTH_CLIENT_ID,
  // アプリの redirect URI を使ってください。サンプルアプリは:
  // "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
  redirectUri = "com.mapconductor.plugin.provider.geolocation:/oauth2redirect"
)
```

3. 認可フローを開始(UI):

```kotlin
startActivity(provider.buildAuthorizationIntent())
```

4. リダイレクトを受け取った Activity で結果を処理:

- `handleAuthorizationResponse(intent)` を呼び出して状態を保存します。

5. バックグラウンド処理用に登録:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

## end-to-end 配線(詳細: 書き方 -> 結果)

### 1) リダイレクト用 Activity を用意する(redirect URI -> コールバック)

書くこと:

- カスタムスキームの redirect URI を受け取る Activity(または handler)を用意します。

Manifest 例(redirect URI の scheme/path と一致させる):

```xml
<activity
  android:name=".auth.AppAuthSignInActivity"
  android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data
      android:scheme="com.mapconductor.plugin.provider.geolocation"
      android:path="/oauth2redirect" />
  </intent-filter>
</activity>
```

得られるもの:

- ブラウザ/AppAuth のフローが `Intent` としてアプリへ戻るようになります。

### 2) `AuthState` を永続化する(UI -> background refresh)

書くこと:

- リダイレクトを受け取った Activity で handler を呼びます(コード交換 + 状態保存)。

```kotlin
val ok = provider.handleAuthorizationResponse(intent)
```

得られるもの:

- 認可が成功すると、バックグラウンドでも `provider.getAccessToken()` が動作可能になります(AppAuth が refresh)。

### 3) background provider として登録する(datamanager -> auth)

書くこと:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

得られるもの:

- `:datamanager` の worker/manager が UI なしで Drive upload を行えます。

## 注意

- 本モジュールはリダイレクト用 Activity / intent-filter を提供しません。ホストアプリ側で用意してください。
- `GoogleDriveTokenProvider` はバックグラウンドから UI を開始してはいけません。UI が必要な場合は `null` を返す設計です。
