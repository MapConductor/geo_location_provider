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

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

class GoogleAuthRepository(private val context: Context) {
    private val tag = "DriveAuth"

    private val gso: GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DRIVE_FILE_SCOPE))
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
            val oauth2Scope = "oauth2:$DRIVE_FILE_SCOPE"
            @Suppress("DEPRECATION")
            runCatching {
                // Account は non-null 必須
                val account: Account = acct.account ?: Account(email, "com.google")
                GoogleAuthUtil.getToken(context, account, oauth2Scope)
            }.onFailure { Log.w(tag, "getToken failed", it) }.getOrNull()
        }

    fun signOut(activity: Activity) {
        GoogleSignIn.getClient(activity, gso).signOut()
    }
}
