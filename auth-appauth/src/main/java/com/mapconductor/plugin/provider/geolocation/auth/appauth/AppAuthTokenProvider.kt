package com.mapconductor.plugin.provider.geolocation.auth.appauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleDriveTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import kotlin.coroutines.resume

/**
 * AppAuth-based implementation of GoogleDriveTokenProvider.
 *
 * This implementation uses the AppAuth library (https://github.com/openid/AppAuth-Android)
 * which follows OAuth 2.0 best practices including PKCE for native apps (RFC 8252).
 *
 * Features:
 * - Standards-compliant OAuth 2.0 flow
 * - PKCE support for enhanced security
 * - Works with any OAuth 2.0 provider (not just Google)
 * - Automatic token refresh
 * - Secure token storage via AuthState serialization
 *
 * Usage:
 * ```
 * val tokenProvider = AppAuthTokenProvider(context, clientId, redirectUri)
 *
 * // Start authorization flow
 * val intent = tokenProvider.buildAuthorizationIntent()
 * startActivityForResult(intent, REQUEST_CODE)
 *
 * // Handle response
 * tokenProvider.handleAuthorizationResponse(data)
 *
 * // Get token
 * val token = tokenProvider.getAccessToken()
 * ```
 *
 * @param context Application context
 * @param clientId OAuth 2.0 client ID from Google Cloud Console
 * @param redirectUri Redirect URI configured in Google Cloud Console (e.g., "com.yourapp:/oauth2redirect")
 * @param scopes List of OAuth scopes to request (defaults to Drive file access)
 */
class AppAuthTokenProvider(
    private val context: Context,
    private val clientId: String,
    private val redirectUri: String,
    private val scopes: List<String> = listOf(
        "https://www.googleapis.com/auth/drive.file",
        "https://www.googleapis.com/auth/drive.metadata.readonly"
    )
) : GoogleDriveTokenProvider {

    companion object {
        private const val TAG = "AppAuthTokenProvider"
        private const val PREFS_NAME = "appauth_state"
        private const val KEY_AUTH_STATE = "auth_state"

        // Google OAuth 2.0 endpoints
        private const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    }

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse(AUTHORIZATION_ENDPOINT),
        Uri.parse(TOKEN_ENDPOINT)
    )

    private val authService = AuthorizationService(context)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var authState: AuthState
        get() {
            val json = prefs.getString(KEY_AUTH_STATE, null)
            return if (json != null) {
                AuthState.jsonDeserialize(json)
            } else {
                AuthState(serviceConfig)
            }
        }
        set(value) {
            prefs.edit().putString(KEY_AUTH_STATE, value.jsonSerializeString()).apply()
        }

    /**
     * Build an authorization intent to start the OAuth flow.
     *
     * Launch this intent with startActivityForResult and handle the response
     * with handleAuthorizationResponse().
     */
    fun buildAuthorizationIntent(): Intent {
        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(redirectUri)
        )
            .setScopes(scopes)
            .build()

        return authService.getAuthorizationRequestIntent(authRequest)
    }

    /**
     * Handle the authorization response from the OAuth flow.
     *
     * Call this from onActivityResult() with the intent data.
     *
     * @param intent The intent from onActivityResult
     * @return true if authorization succeeded, false otherwise
     */
    suspend fun handleAuthorizationResponse(intent: Intent?): Boolean = withContext(Dispatchers.IO) {
        if (intent == null) {
            Log.w(TAG, "Intent is null")
            return@withContext false
        }

        val response = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)

        if (exception != null) {
            Log.w(TAG, "Authorization failed", exception)
            return@withContext false
        }

        if (response == null) {
            Log.w(TAG, "No authorization response")
            return@withContext false
        }

        // Exchange authorization code for tokens
        val tokenRequest = response.createTokenExchangeRequest()
        val tokenResponse = performTokenRequest(tokenRequest)

        if (tokenResponse != null) {
            val newAuthState = AuthState(serviceConfig)
            newAuthState.update(response, tokenResponse, exception)
            authState = newAuthState
            true
        } else {
            false
        }
    }

    override suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val currentAuthState = authState

        if (!currentAuthState.isAuthorized) {
            Log.d(TAG, "Not authorized")
            return@withContext null
        }

        // AppAuth handles token refresh automatically
        suspendCancellableCoroutine { continuation ->
            currentAuthState.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                if (ex != null) {
                    Log.w(TAG, "Failed to get fresh token", ex)
                    continuation.resume(null)
                } else {
                    continuation.resume(accessToken)
                }
            }

            continuation.invokeOnCancellation {
                // Cleanup if needed
            }
        }
    }

    override suspend fun refreshToken(): String? = withContext(Dispatchers.IO) {
        val currentAuthState = authState
        val refreshToken = currentAuthState.refreshToken

        if (refreshToken == null) {
            Log.w(TAG, "No refresh token available")
            return@withContext null
        }

        val tokenRequest = TokenRequest.Builder(serviceConfig, clientId)
            .setRefreshToken(refreshToken)
            .build()

        val tokenResponse = performTokenRequest(tokenRequest)

        if (tokenResponse != null) {
            currentAuthState.update(tokenResponse, null)
            authState = currentAuthState
            tokenResponse.accessToken
        } else {
            null
        }
    }

    override suspend fun isAuthenticated(): Boolean {
        return authState.isAuthorized
    }

    /**
     * Sign out the user by clearing the stored auth state.
     */
    fun signOut() {
        authState = AuthState(serviceConfig)
        prefs.edit().clear().apply()
    }

    /**
     * Clean up resources. Call this when done with the provider.
     */
    fun dispose() {
        authService.dispose()
    }

    private suspend fun performTokenRequest(request: TokenRequest): net.openid.appauth.TokenResponse? =
        suspendCancellableCoroutine { continuation ->
            authService.performTokenRequest(request) { response, ex ->
                if (ex != null) {
                    Log.w(TAG, "Token request failed", ex)
                    continuation.resume(null)
                } else {
                    continuation.resume(response)
                }
            }

            continuation.invokeOnCancellation {
                // Cleanup if needed
            }
        }
}
