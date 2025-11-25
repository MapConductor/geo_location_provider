package com.mapconductor.plugin.provider.geolocation.ui.settings

/**
 * Authentication methods supported for Google Drive integration.
 *
 * - CREDENTIAL_MANAGER : Android Credential Manager + Identity API
 * - APPAUTH            : AppAuth (AuthorizationService + AuthState)
 */
enum class DriveAuthMethod(
    val storageValue: String,
    val label: String
) {
    CREDENTIAL_MANAGER(
        storageValue = "credential_manager",
        label = "Credential Manager"
    ),
    APPAUTH(
        storageValue = "appauth",
        label = "AppAuth (OAuth 2.0)"
    );

    companion object {
        fun fromStorage(value: String): DriveAuthMethod =
            values().firstOrNull { it.storageValue == value }
                ?: CREDENTIAL_MANAGER
    }
}

