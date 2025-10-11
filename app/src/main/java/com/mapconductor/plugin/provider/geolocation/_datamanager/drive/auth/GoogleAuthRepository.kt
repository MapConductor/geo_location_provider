package com.mapconductor.plugin.provider.geolocation._datamanager.drive.auth

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

// --- 追加したいスコープ ---
private const val DRIVE_FILE_SCOPE      = "https://www.googleapis.com/auth/drive.file"
private const val DRIVE_METADATA_SCOPE  = "https://www.googleapis.com/auth/drive.metadata.readonly"
// ※フルアクセスにするなら ↓ を1本だけ使う（代替案）
// private const val DRIVE_FULL_SCOPE     = "https://www.googleapis.com/auth/drive"

class GoogleAuthRepository(private val context: Context) {
    private val tag = "DriveAuth"

    // ★ 複数スコープ要求（drive.file + drive.metadata.readonly）
    //   フル権限にするなら .requestScopes(Scope(DRIVE_FULL_SCOPE)) に置き換え
    private val gso: GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(
            Scope(DRIVE_FILE_SCOPE),
            Scope(DRIVE_METADATA_SCOPE)
            // Scope(DRIVE_FULL_SCOPE)  // ← フル権限に切替える場合はこちらを使い、上2つは外す
        )
        .build()

    fun buildSignInIntent(): Intent =
        GoogleSignIn.getClient(context, gso).signInIntent

    suspend fun handleSignInResult(data: Intent?): GoogleSignInAccount? =
        withContext(Dispatchers.IO) {
            runCatching {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                task.await()
            }.onFailure { Log.w(tag, "SignIn failed", it) }.getOrNull()
        }

    suspend fun getAccessTokenOrNull(): String? =
        withContext(Dispatchers.IO) {
            val acct = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
            val email = acct.email ?: return@withContext null

            // ★ 複数スコープはスペース区切りで "oauth2:scope1 scope2" の形式に
            //    フル権限にする場合は scopes = listOf(DRIVE_FULL_SCOPE)
            val scopes = listOf(
                DRIVE_FILE_SCOPE,
                DRIVE_METADATA_SCOPE
            )
            val oauth2Scope = "oauth2:" + scopes.joinToString(" ")

            @Suppress("DEPRECATION")
            runCatching {
                // Account は non-null 必須。無ければ email から生成
                val account: Account = acct.account ?: Account(email, "com.google")
                GoogleAuthUtil.getToken(context, account, oauth2Scope)
            }.onFailure { Log.w(tag, "getToken failed", it) }.getOrNull()
        }

    fun signOut(activity: Activity) {
        GoogleSignIn.getClient(activity, gso).signOut()
        // 必要であれば revokeAccess() も：
        // GoogleSignIn.getClient(activity, gso).revokeAccess()
    }
}
