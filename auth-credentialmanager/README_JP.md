# :auth-credentialmanager (Credential Manager による Drive トークン供給)

Gradle モジュール: `:auth-credentialmanager`

本モジュールは Android Credential Manager (サインイン) と Google Identity AuthorizationClient (スコープ認可とアクセストークン取得)を使って `GoogleDriveTokenProvider` を実装します。

## モジュール境界の契約

ホスト側がやること:

- `signIn(...)` を Activity から呼ぶ(UI が必要)
- 必要な Drive スコープが未付与の場合、対話的な認可フローをホスト側で行う

得られるもの(効果):

- スコープが付与済みであれば `getAccessToken()` が UI なしでトークンを返す
- 対話が必要な場合は `null` を返すため、バックグラウンド処理は "未認可" として扱える

## 提供するもの

- `CredentialManagerTokenProvider`: `GoogleDriveTokenProvider` 実装
  - `signIn(...)` は Activity から呼ぶ必要があります
  - `getAccessToken()` はスコープが既に付与済みの場合のみトークンを返します

対象:

- `auth-credentialmanager/src/main/java/com/mapconductor/plugin/provider/geolocation/auth/credentialmanager/CredentialManagerTokenProvider.kt`

## 使い方(概要)

1. 依存関係を追加:

```kotlin
dependencies {
  implementation(project(":auth-credentialmanager"))
}
```

2. プロバイダを作成:

```kotlin
val provider = CredentialManagerTokenProvider(
  context = activity,
  serverClientId = BuildConfig.CREDENTIAL_MANAGER_SERVER_CLIENT_ID
)
```

3. サインイン(UI):

```kotlin
val credential = provider.signIn(activity)
```

4. バックグラウンド処理用に登録:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

## end-to-end の流れ(詳細: 書き方 -> 結果)

本モジュールは、バックグラウンド処理が UI を開始しない設計になっています。

### 手順 1: サインイン(アカウント選択)

書くこと:

```kotlin
val idCredential = provider.signIn(activity)
```

得られるもの:

- Credential Manager 経由で Google アカウントが選択/サインインされます。
- キャンセル等の場合は `null` になるため、アップロードは無効のままにするのが安全です。

### 手順 2: Drive スコープの付与(必要時は対話)

書くこと:

- まず `getAccessToken()` を試し、`null` の場合はスコープ未付与の可能性が高いです。
- その場合、ホスト側で対話的な認可(AuthorizationClient の resolution)を完了させてから再試行します。

得られるもの:

- スコープ付与後は `provider.getAccessToken()` が UI なしでトークンを返せるようになり、バックグラウンドアップロードが可能になります。

### 手順 3: バックグラウンドアップロード用に登録

書くこと:

```kotlin
DriveTokenProviderRegistry.registerBackgroundProvider(provider)
```

得られるもの:

- `:datamanager` の worker/manager がバックグラウンドで `getAccessToken()` を呼べます。
- 対話が必要な状態では `null` が返り、安全にアップロードがスキップされます。

## 注意

- バックグラウンドから UI を開始してはいけません。対話が必要な場合、`getAccessToken()` は設計上 `null` を返します。
- Credential Manager は "web/server" クライアント ID (`serverClientId`) を使用してください。
