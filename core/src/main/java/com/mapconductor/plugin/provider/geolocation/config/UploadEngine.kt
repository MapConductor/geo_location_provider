package com.mapconductor.plugin.provider.geolocation.config

/**
 * Upload engine type used by workers and settings.
 *
 * Currently supported:
 * - none   : no upload is performed
 * - kotlin : Kotlin / OkHttp based uploader
 */
enum class UploadEngine(val wire: String) {
    NONE("none"),
    KOTLIN("kotlin");

    companion object {
        fun fromString(s: String?): UploadEngine =
            entries.firstOrNull { it.wire.equals(s, ignoreCase = true) } ?: NONE
    }
}

