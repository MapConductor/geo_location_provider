package com.mapconductor.plugin.provider.geolocation.drive.auth

/**
 * Interface for providing Google Drive API access tokens.
 *
 * This interface allows the library to be authentication-agnostic.
 * Host applications can implement this interface using their preferred
 * authentication method (Credential Manager, AppAuth, custom OAuth, etc.).
 *
 * Example implementations are provided in separate modules:
 * - auth-credential-manager: Using Android Credential Manager + AuthorizationClient
 * - auth-appauth: Using AppAuth library
 * - auth-legacy: Using deprecated GoogleAuthUtil (not recommended)
 */
interface GoogleDriveTokenProvider {
    /**
     * Get a valid access token for Google Drive API.
     *
     * This method should return a fresh access token that can be used
     * to authenticate requests to the Google Drive API.
     *
     * @return A valid access token, or null if authentication failed or user is not signed in
     */
    suspend fun getAccessToken(): String?

    /**
     * Refresh the access token when it expires.
     *
     * This method is called when the API returns a 401 Unauthorized response,
     * indicating that the current token has expired. Implementations should
     * attempt to refresh the token and return the new one.
     *
     * @return A new valid access token, or null if refresh failed
     */
    suspend fun refreshToken(): String?

    /**
     * Check if the user is currently authenticated.
     *
     * @return true if the user is authenticated and can provide tokens
     */
    suspend fun isAuthenticated(): Boolean = getAccessToken() != null
}
