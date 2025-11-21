package com.mapconductor.plugin.provider.geolocation.drive.auth

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val DRIVE_FILE_SCOPE     = "https://www.googleapis.com/auth/drive.file"
private const val DRIVE_METADATA_SCOPE = "https://www.googleapis.com/auth/drive.metadata.readonly"
// private const val DRIVE_FULL_SCOPE     = "https://www.googleapis.com/auth/drive"

/**
 * Legacy implementation of GoogleDriveTokenProvider using deprecated GoogleAuthUtil.
 *
 * @deprecated This implementation uses deprecated GoogleAuthUtil.getToken() which will be
 * removed in future Android versions. Consider migrating to:
 * - Credential Manager + AuthorizationClient (recommended for Android 14+)
 * - AppAuth library (cross-platform OAuth 2.0)
 *
 * This is kept for backward compatibility but should not be used in new code.
 */
@Deprecated(
    message = "Use Credential Manager or AppAuth instead",
    replaceWith = ReplaceWith("CredentialManagerTokenProvider or AppAuthTokenProvider")
)
class GoogleAuthRepository(private val context: Context) : GoogleDriveTokenProvider {
    private val tag = "DriveAuth"

    private val gso: GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(
            Scope(DRIVE_FILE_SCOPE),
            Scope(DRIVE_METADATA_SCOPE)
        )
        .build()

    fun buildSignInIntent(): Intent =
        GoogleSignIn.getClient(context, gso).signInIntent

    suspend fun handleSignInResult(data: Intent?): GoogleSignInAccount? =
        withContext(Dispatchers.IO) {
            try {
                GoogleSignIn.getSignedInAccountFromIntent(data).await()
            } catch (t: Throwable) {
                Log.w(tag, "SignIn failed", t)
                null
            }
        }

    override suspend fun getAccessToken(): String? =
        withContext(Dispatchers.IO) {
            val acct = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            val email = acct.email ?: return@withContext null

            val scopes = listOf(
                DRIVE_FILE_SCOPE,
                DRIVE_METADATA_SCOPE
            )
            val oauth2Scope = "oauth2:" + scopes.joinToString(" ")

            @Suppress("DEPRECATION")
            try {
                val account: Account = acct.account ?: Account(email, "com.google")
                GoogleAuthUtil.getToken(context, account, oauth2Scope)
            } catch (t: Throwable) {
                Log.w(tag, "getToken failed", t)
                null
            }
        }

    override suspend fun refreshToken(): String? {
        // GoogleAuthUtil 側でトークンの再取得が処理されるため、getAccessToken() を呼び直す。
        return getAccessToken()
    }

    @Deprecated("Use getAccessToken() instead", ReplaceWith("getAccessToken()"))
    suspend fun getAccessTokenOrNull(): String? = getAccessToken()

    fun signOut(activity: Activity) {
        GoogleSignIn.getClient(activity, gso).signOut()
        // 必要であれば revokeAccess() も併用することを検討する。
        // GoogleSignIn.getClient(activity, gso).revokeAccess()
    }
}

