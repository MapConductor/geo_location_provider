package com.mapconductor.plugin.provider.geolocation.auth.credentialmanager

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleDriveTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Credential Manager-based implementation of GoogleDriveTokenProvider.
 *
 * This is the recommended approach for Android 14+ (API 34+) as of 2025.
 * It uses the new Android Credential Manager API for authentication and
 * AuthorizationClient for authorization (accessing Google Drive API).
 *
 * Key Features:
 * - Modern Android authentication (replaces legacy Google Sign-In)
 * - Separation of authentication (sign-in) and authorization (API access)
 * - Better security and user experience
 * - Integration with system credential providers
 *
 * Architecture:
 * 1. Authentication: Credential Manager + GetGoogleIdOption
 *    - Signs in the user and gets basic profile info
 *    - Sets the default Google account for the app
 *
 * 2. Authorization: GoogleSignInClient with requestScopes
 *    - Requests OAuth scopes for Google Drive API access
 *    - Uses the account from step 1 as default
 *    - Returns access tokens for API calls
 *
 * Usage:
 * ```
 * val tokenProvider = CredentialManagerTokenProvider(context, serverClientId)
 *
 * // First, authenticate the user
 * val signedIn = tokenProvider.signIn(activity)
 *
 * // Then get access token for Drive API
 * val token = tokenProvider.getAccessToken()
 * ```
 *
 * Requirements:
 * - minSdk 26 (Credential Manager has compatibility support)
 * - Google Cloud Console setup with OAuth 2.0 credentials
 * - Web application client ID (server client ID)
 *
 * @param context Application context
 * @param serverClientId OAuth 2.0 Web application client ID from Google Cloud Console
 * @param scopes List of OAuth scopes to request (defaults to Drive file access)
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

    private val credentialManager = CredentialManager.create(context)

    private val googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(*scopes.map { Scope(it) }.toTypedArray())
        .build()

    /**
     * Sign in the user using Credential Manager.
     *
     * This should be called from an Activity context.
     * After successful sign-in, the user's Google account becomes the default
     * account for the app, and can be used for authorization requests.
     *
     * @return GoogleIdTokenCredential if sign-in succeeded, null otherwise
     */
    suspend fun signIn(activity: Activity): GoogleIdTokenCredential? = withContext(Dispatchers.Main) {
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result: GetCredentialResponse = credentialManager.getCredential(
                context = activity,
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

    override suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val account: GoogleSignInAccount = GoogleSignIn.getLastSignedInAccount(context)
                ?: run {
                    Log.w(TAG, "No signed-in account")
                    return@withContext null
                }

            val email = account.email
            if (email.isNullOrBlank()) {
                Log.w(TAG, "Signed-in account does not expose email")
                return@withContext null
            }

            val hasScopes = scopes.all { scope ->
                GoogleSignIn.hasPermissions(account, Scope(scope))
            }
            if (!hasScopes) {
                Log.w(TAG, "Missing required scopes, need to request authorization")
                return@withContext null
            }

            val oauthScope = "oauth2:" + scopes.joinToString(" ")
            val googleAccount: Account = account.account ?: Account(email, "com.google")

            @Suppress("DEPRECATION")
            GoogleAuthUtil.getToken(context, googleAccount, oauthScope)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get access token", e)
            null
        }
    }

    override suspend fun refreshToken(): String? {
        // In Credential Manager approach, refreshing is handled by getAccessToken()
        return getAccessToken()
    }

    override suspend fun isAuthenticated(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null
    }

    fun getLastSignedInEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email

    /**
     * Sign out the user.
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
