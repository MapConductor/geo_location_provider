package com.mapconductor.plugin.provider.geolocation.auth.credentialmanager

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleDriveTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * GoogleDriveTokenProvider implementation based on Android Credential Manager and
 * Identity AuthorizationClient.
 *
 * Responsibilities:
 * - Sign the user in with Credential Manager and obtain a Google account.
 * - Use Identity AuthorizationClient to acquire an access token for Drive scopes.
 *
 * Usage sketch:
 *
 * val provider = CredentialManagerTokenProvider(
 *     context = activity,
 *     serverClientId = "YOUR_OAUTH_CLIENT_ID"
 * )
 *
 * // 1. Start sign in from an Activity when UI is available
 * val idCred = provider.signIn()
 *
 * // 2. Later, with an already signed in account, obtain a token without UI
 * val token = provider.getAccessToken()
 *
 * Notes:
 * - signIn must be called with an Activity context because of Credential Manager requirements.
 * - getAccessToken succeeds only when the requested scopes are already granted.
 *   If user interaction is required, this method returns null and the app should run
 *   an AuthorizationClient flow with resolution on its own.
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

    @Volatile
    private var lastSignInErrorSummary: String? = null

    fun lastSignInError(): String? = lastSignInErrorSummary

    private fun setLastSignInError(message: String) {
        lastSignInErrorSummary = message
        Log.w(TAG, message)
    }

    private fun findActivityOrNull(ctx: Context): Activity? {
        var current: Context? = ctx
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return if (current is Activity) current else null
    }

    /**
     * Starts a sign in flow using Credential Manager and returns a GoogleIdTokenCredential
     * when successful.
     *
     * Requirements:
     * - Must be called with an Activity context (pass an Activity as activityContext).
     * - On success the credential contains idToken, subject and other basic fields.
     * - This method does not yet request Drive scopes; those are requested later via
     *   AuthorizationClient in getAccessToken.
     */
    suspend fun signIn(activityContext: Context = context): GoogleIdTokenCredential? =
        withContext(Dispatchers.IO) {
            lastSignInErrorSummary = null

            if (serverClientId.isBlank() || serverClientId.startsWith("YOUR_")) {
                setLastSignInError("Credential Manager sign-in blocked: serverClientId is not set.")
                return@withContext null
            }

            val activity = findActivityOrNull(activityContext)
            if (activity == null) {
                setLastSignInError("Credential Manager sign-in failed: Activity context is required.")
                return@withContext null
            }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            try {
                val result: GetCredentialResponse = withContext(Dispatchers.Main) {
                    credentialManager.getCredential(
                        context = activity,
                        request = request
                    )
                }

                when (val credential = result.credential) {
                    is CustomCredential -> {
                        if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            GoogleIdTokenCredential.createFrom(credential.data)
                        } else {
                            setLastSignInError("Credential Manager sign-in failed: unexpected credential type.")
                            null
                        }
                    }

                    else -> {
                        setLastSignInError("Credential Manager sign-in failed: unexpected credential class.")
                        null
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                setLastSignInError("Credential Manager sign-in canceled.")
                null
            } catch (e: NoCredentialException) {
                setLastSignInError("Credential Manager sign-in failed: no eligible credentials.")
                null
            } catch (e: GetCredentialException) {
                Log.w(TAG, "Sign-in failed (GetCredentialException)", e)
                setLastSignInError(
                    "Credential Manager sign-in failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                )
                null
            } catch (e: Exception) {
                Log.w(TAG, "Sign-in failed", e)
                setLastSignInError(
                    "Credential Manager sign-in failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                )
                null
            }
        }

    /**
     * Obtains a Drive access token from the Identity Authorization API.
     *
     * Preconditions:
     * - The user is already signed in and the requested scopes are already granted.
     *   In that case result.hasResolution() is false and accessToken is available.
     *
     * If user interaction is still required:
     * - result.hasResolution() will be true.
     * - This method returns null and the app should start an authorize() flow with
     *   resolution on its own (for example via ActivityResult).
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
                // User interaction is required; leave this to the app layer
                Log.w(
                    TAG,
                    "Authorization requires user interaction (hasResolution == true). " +
                        "Handle the interactive flow in the app layer."
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
     * Token refresh is delegated to AuthorizationClient.authorize, so this simply calls
     * getAccessToken again.
     */
    override suspend fun refreshToken(): String? {
        return getAccessToken()
    }

    /**
     * Signs the user out.
     *
     * This implementation clears Credential Manager state so that a subsequent sign-in
     * requires user selection again.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Sign-out failed", e)
        }
    }
}
