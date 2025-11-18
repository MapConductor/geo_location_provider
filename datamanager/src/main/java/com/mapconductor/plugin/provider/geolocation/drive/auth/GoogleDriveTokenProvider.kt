package com.mapconductor.plugin.provider.geolocation.drive.auth

/**
 * Interface for providing OAuth2 access tokens for the Google Drive API.
 *
 * This interface is the ONLY thing the upload layer depends on for authentication.
 * Host applications are responsible for implementing this using their preferred
 * auth mechanism (Credential Manager, AppAuth, legacy GoogleAuthUtil wrapper, etc).
 *
 * Design notes:
 * - The token string MUST NOT include the "Bearer " prefix.
 * - Methods MUST NOT throw in normal failure cases (network error, not signed in, etc).
 *   Instead, they should log and return null.
 * - "UI を伴うフローが必要" な場合も、ここでは null を返すだけにし、
 *   実際の UI フローはアプリ側で実装する前提とする。
 *
 * Example implementations are provided in separate modules:
 * - :auth-credentialmanager  … Android Credential Manager + Identity AuthorizationClient
 * - :auth-appauth           … AppAuth with AuthorizationService + AuthState
 * - :datamanager (legacy)   … GoogleAuthRepository (deprecated, not recommended for new code)
 */
interface GoogleDriveTokenProvider {

    /**
     * Return a short-lived OAuth2 access token for calling the Google Drive REST API.
     *
     * Requirements:
     * - The returned value MUST be just the raw token (e.g. "ya29.a0Af…").
     *   Callers will add the "Bearer " prefix themselves in Authorization headers.
     * - If the user is not signed in, consent has been revoked, the token cannot be
     *   obtained without showing UI, or any other recoverable error occurs,
     *   implementations MUST return null instead of throwing.
     *
     * Typical behavior:
     * - If an in-memory / cached token is still valid, return it as-is.
     * - Otherwise, attempt to obtain a fresh token **without** starting any UI.
     * - Log any errors for debugging, but do not crash the app.
     *
     * @return A valid access token string, or null if it cannot be obtained
     *         without user interaction.
     */
    suspend fun getAccessToken(): String?

    /**
     * Force retrieval of a fresh access token.
     *
     * Responsibilities:
     * - Prefer refreshing an existing credential (refresh token / offline session)
     *   without user interaction.
     * - If the refresh fails (e.g. refresh token revoked, network error, etc),
     *   log the error and return null.
     *
     * NOTE:
     * - For simple implementations, this may behave the same as [getAccessToken].
     *   More advanced implementations (e.g. AppAuth) can distinguish between
     *   "cached token" and "forced refresh".
     *
     * @return A new valid access token, or null if refresh failed
     */
    suspend fun refreshToken(): String?

    /**
     * Convenience check for whether the caller can reasonably expect to obtain
     * an access token **without user interaction**.
     *
     * Default behavior:
     * - Calls [getAccessToken] and returns true if the result is non-null.
     *
     * Implementations MAY override this if they can perform a cheaper check
     * (for example, checking whether a valid AuthState / credential is present
     * before actually contacting the network).
     *
     * @return true if the user is authenticated and a token can be provided
     *         without starting any UI flow
     */
    suspend fun isAuthenticated(): Boolean = getAccessToken() != null
}
