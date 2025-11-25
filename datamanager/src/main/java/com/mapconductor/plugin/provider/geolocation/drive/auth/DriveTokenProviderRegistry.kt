package com.mapconductor.plugin.provider.geolocation.drive.auth

/**
 * Registry for GoogleDriveTokenProvider used from background threads (Worker, etc).
 *
 * Host app should register Credential Manager / AppAuth implementations at startup.
 * datamanager then uses this registry to obtain providers without knowing app wiring.
 *
 * - UI flows are responsibility of the host app and must not be launched from here.
 * - Provider behaves like a process-wide singleton within the app.
 */
object DriveTokenProviderRegistry {

    @Volatile
    private var backgroundProvider: GoogleDriveTokenProvider? = null

    /**
     * Register token provider used from background processing.
     * Passing null clears the current registration.
     */
    fun registerBackgroundProvider(provider: GoogleDriveTokenProvider?) {
        backgroundProvider = provider
    }

    /**
     * Get registered background token provider, or null when not registered.
     */
    fun getBackgroundProvider(): GoogleDriveTokenProvider? = backgroundProvider
}

