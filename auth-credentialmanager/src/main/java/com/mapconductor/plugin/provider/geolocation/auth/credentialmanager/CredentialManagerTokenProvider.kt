package com.mapconductor.plugin.provider.geolocation.auth.credentialmanager

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleDriveTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Google Drive 用のト�EクンめEAndroid Credential Manager + AuthorizationClient で取得する実裁E��E
 *
 * 役割:
 * - Credential Manager を使っぁEGoogle アカウント�Eサインイン
 * - Identity API (AuthorizationClient) を使っぁEDrive 向けアクセスト�Eクンの取征E
 *
 * 利用イメージ:
 *
 * val provider = CredentialManagerTokenProvider(
 *     context = activity,
 *     serverClientId = "YOUR_OAUTH_CLIENT_ID"
 * )
 *
 * // 1. どこかの画面でサインイン�E�EIあり�E�E
 * val idCred = provider.signIn()
 *
 * // 2. サインイン済み状態でト�Eクン取得！EIなし！E
 * val token = provider.getAccessToken()
 *
 * 注愁E
 * - signIn() は Activity コンチE��ストで呼ぶこと�E�Eredential Manager の制紁E��E
 * - getAccessToken() は「既にスコープ許可済み」�E場合にのみ成功する、E
 *   ユーザー操作が忁E��な場合�E hasResolution()==true となり、ここでは null を返す、E
 *   そ�E場合�Eアプリ側で AuthorizationClient.authorize() の解決付きフローを絁E�Eこと、E
 */
@Suppress("DEPRECATION")
class CredentialManagerTokenProvider(
    private val context: Context,
    private val serverClientId: String,
    private val scopes: List<String> = listOf(
        "https://www.googleapis.com/auth/drive.file",
        "https://www.googleapis.com/auth/drive.metadata.readonly"
    )
) : GoogleDriveTokenProvider {

    companion object {
        private const val TAG = "CredentialManagerAuth"
    }

    private val credentialManager: CredentialManager = CredentialManager.create(context)

    /**
     * GoogleSignInOptions は signOut() 時�Eクライアント生成にだけ使ぁE��E
     * アクセスト�Eクン自体�E AuthorizationClient から取得する、E
     */
    @Suppress("DEPRECATION")
    private val googleSignInOptions: GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).apply {
            requestEmail()

            val scopeObjects = scopes.map { Scope(it) }
            if (scopeObjects.isNotEmpty()) {
                requestScopes(scopeObjects.first(), *scopeObjects.drop(1).toTypedArray())
            }
        }.build()

    /**
     * Credential Manager を使ってサインインする、E
     *
     * - Activity コンチE��ストで呼ぶこと�E�Eontext には Activity を渡す前提！E
     * - 成功時�E GoogleIdTokenCredential を返す�E�EdToken, subject 等が取れる！E
     * - ここでは Drive スコープまではまだ要求しなぁE��後で AuthorizationClient で要求！E
     */
    suspend fun signIn(activityContext: Context = context): GoogleIdTokenCredential? =
        withContext(Dispatchers.IO) {
            try {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(serverClientId)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result: GetCredentialResponse = credentialManager.getCredential(
                    context = activityContext,
                    request = request
                )

                when (val credential = result.credential) {
                    is CustomCredential -> {
                        if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            GoogleIdTokenCredential.createFrom(credential.data)
                        } else {
                            Log.w(TAG, "Unexpected credential type: ${credential.type}")
                            null
                        }
                    }

                    else -> {
                        Log.w(TAG, "Unexpected credential class: ${result.credential}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sign-in failed", e)
                null
            }
        }

    /**
     * Drive 用のアクセスト�EクンめEIdentity Authorization API から取得する、E
     *
     * 前提:
     * - ユーザーが既にサインインしてぁE��、要求するスコープが許可済みなめE
     *   -> hasResolution() == false となり、ここから直接 accessToken が取れる、E
     * - まだスコープが許可されてぁE��ぁE/ ユーザー操作が忁E��な場吁E
     *   -> hasResolution() == true となる。この場合このメソチE��は null を返し、E
     *      アプリ側で ActivityResult 等を使って解決付き authorize() を実行すべき、E
     */
    override suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val authClient = Identity.getAuthorizationClient(context)

            val requestedScopes = scopes.map { Scope(it) }

            val request = AuthorizationRequest.Builder()
                .setRequestedScopes(requestedScopes)
                .build()

            val result = authClient.authorize(request).await()

            if (result.hasResolution()) {
                // ここでユーザー操作を伴ぁE��ローを開始する�Eはライブラリの責務外とする、E
                Log.w(
                    TAG,
                    "Authorization requires user interaction (hasResolution==true). " +
                            "Handle this in the app layer."
                )
                return@withContext null
            }

            val token = result.accessToken
            if (token.isNullOrBlank()) {
                Log.w(TAG, "AuthorizationResult.accessToken is null or blank")
                null
            } else {
                Log.d(TAG, "Access token acquired (length=${token.length})")
                token
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get access token", e)
            null
        }
    }

    /**
     * Token refresh は AuthorizationClient.authorize() に丸投げできるので、E
     * 単純に getAccessToken() を呼び直す、E
     */
    override suspend fun refreshToken(): String? {
        return getAccessToken()
    }

    /**
     * サインアウト、E
     *
     * ここでは簡易的に GoogleSignInClient の signOut() だけ呼ぶ、E
     * �E�忁E��ならアプリ側で CredentialManager の clearCredentialState() なども併用すること、E
     */
    @Suppress("DEPRECATION")
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            val client = GoogleSignIn.getClient(context, googleSignInOptions)
            client.signOut().await()
        } catch (e: Exception) {
            Log.w(TAG, "Sign-out failed", e)
        }
    }
}
