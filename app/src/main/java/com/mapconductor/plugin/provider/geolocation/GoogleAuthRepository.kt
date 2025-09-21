package com.mapconductor.plugin.provider.geolocation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GoogleAuthRepository {

    private val DRIVE_FILE_SCOPE = Scope("https://www.googleapis.com/auth/drive.file")

    private fun gso(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(DRIVE_FILE_SCOPE)
            .build()

    fun client(context: Context): GoogleSignInClient =
        GoogleSignIn.getClient(context, gso())

    /** サインイン起動Intent（Activity Result API で launch） */
    fun signInIntent(context: Context): Intent = client(context).signInIntent

    /** onActivityResult からアカウント取得（失敗時 null） */
    fun handleSignInResult(data: Intent?): GoogleSignInAccount? = runCatching {
        GoogleSignIn.getSignedInAccountFromIntent(data)
            .getResult(ApiException::class.java)
    }.getOrNull()

    /** 直近のアカウント（なければ null） */
    fun lastAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    /** Drive.file スコープ付与済みか */
    fun hasDriveScope(account: GoogleSignInAccount?): Boolean =
        account != null && GoogleSignIn.hasPermissions(account, DRIVE_FILE_SCOPE)

    /** スコープ追加同意をUIで要求（必要時のみ） */
    fun requestDriveScope(activity: Activity, account: GoogleSignInAccount) {
        if (!hasDriveScope(account)) {
            GoogleSignIn.requestPermissions(activity, /*reqCode*/1001, account, DRIVE_FILE_SCOPE)
        }
    }

    /** アクセストークン取得（Drive.file同意済み前提/UI不要） */
    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val account = lastAccount(context) ?: return@withContext null
        if (!hasDriveScope(account)) return@withContext null

        val scope = "oauth2:${Scopes.EMAIL} ${Scopes.PROFILE} ${DRIVE_FILE_SCOPE.scopeUri}"
        try {
            val acc = account.account ?: return@withContext null
            GoogleAuthUtil.getToken(context, acc, scope)
        } catch (e: UserRecoverableAuthException) {
            Log.w("DriveAuth", "User action required to grant permissions", e); null
        } catch (e: GoogleAuthException) {
            Log.e("DriveAuth", "GoogleAuthException", e); null
        } catch (e: SecurityException) {
            Log.e("DriveAuth", "SecurityException", e); null
        } catch (e: Exception) {
            Log.e("DriveAuth", "Unexpected", e); null
        }
    }

    /** 401等の直後に再取得を促したいときに使用 */
    suspend fun invalidateToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    suspend fun signOut(context: Context) =
        runCatching { client(context).signOut().awaitTask() }

    suspend fun revokeAccess(context: Context) =
        runCatching { client(context).revokeAccess().awaitTask() }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { r -> if (!cont.isCompleted) cont.resume(r) }
        addOnFailureListener { e -> if (!cont.isCompleted) cont.resumeWithException(e) }
        addOnCanceledListener { if (!cont.isCompleted) cont.cancel() }
    }
