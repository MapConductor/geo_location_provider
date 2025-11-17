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
 * Google Drive 用のトークンを Android Credential Manager + AuthorizationClient で取得する実装。
 *
 * 役割:
 * - Credential Manager を使った Google アカウントのサインイン
 * - Identity API (AuthorizationClient) を使った Drive 向けアクセストークンの取得
 *
 * 利用イメージ:
 *
 * val provider = CredentialManagerTokenProvider(
 *     context = activity,
 *     serverClientId = "YOUR_OAUTH_CLIENT_ID"
 * )
 *
 * // 1. どこかの画面でサインイン（UIあり）
 * val idCred = provider.signIn()
 *
 * // 2. サインイン済み状態でトークン取得（UIなし）
 * val token = provider.getAccessToken()
 *
 * 注意:
 * - signIn() は Activity コンテキストで呼ぶこと（Credential Manager の制約）
 * - getAccessToken() は「既にスコープ許可済み」の場合にのみ成功する。
 *   ユーザー操作が必要な場合は hasResolution()==true となり、ここでは null を返す。
 *   その場合はアプリ側で AuthorizationClient.authorize() の解決付きフローを組むこと。
 */
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
     * GoogleSignInOptions は signOut() 時のクライアント生成にだけ使う。
     * アクセストークン自体は AuthorizationClient から取得する。
     */
    private val googleSignInOptions: GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(*scopes.map { Scope(it) }.toTypedArray())
            .build()

    /**
     * Credential Manager を使ってサインインする。
     *
     * - Activity コンテキストで呼ぶこと（context には Activity を渡す前提）
     * - 成功時は GoogleIdTokenCredential を返す（idToken, subject 等が取れる）
     * - ここでは Drive スコープまではまだ要求しない（後で AuthorizationClient で要求）
     */
    suspend fun signIn(): GoogleIdTokenCredential? = withContext(Dispatchers.IO) {
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result: GetCredentialResponse = credentialManager.getCredential(
                context = context,
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
     * Drive 用のアクセストークンを Identity Authorization API から取得する。
     *
     * 前提:
     * - ユーザーが既にサインインしていて、要求するスコープが許可済みなら
     *   -> hasResolution() == false となり、ここから直接 accessToken が取れる。
     * - まだスコープが許可されていない / ユーザー操作が必要な場合
     *   -> hasResolution() == true となる。この場合このメソッドは null を返し、
     *      アプリ側で ActivityResult 等を使って解決付き authorize() を実行すべき。
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
                // ここでユーザー操作を伴うフローを開始するのはライブラリの責務外とする。
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
     * Token refresh は AuthorizationClient.authorize() に丸投げできるので、
     * 単純に getAccessToken() を呼び直す。
     */
    override suspend fun refreshToken(): String? {
        return getAccessToken()
    }

    /**
     * サインアウト。
     *
     * ここでは簡易的に GoogleSignInClient の signOut() だけ呼ぶ。
     * ＋必要ならアプリ側で CredentialManager の clearCredentialState() なども併用すること。
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            val client = GoogleSignIn.getClient(context, googleSignInOptions)
            client.signOut().await()
        } catch (e: Exception) {
            Log.w(TAG, "Sign-out failed", e)
        }
    }
}
