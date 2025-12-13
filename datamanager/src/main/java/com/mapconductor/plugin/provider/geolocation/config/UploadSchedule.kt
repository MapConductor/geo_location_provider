package com.mapconductor.plugin.provider.geolocation.config

/**
 * Upload schedule for backups.
 *
 * - NONE     : no automatic upload.
 * - NIGHTLY  : midnight export worker only.
 * - REALTIME : realtime upload driven by new samples.
 */
enum class UploadSchedule(val wire: String) {
    NONE("none"),
    NIGHTLY("nightly"),
    REALTIME("realtime");

    companion object {
        fun fromString(s: String?): UploadSchedule =
            entries.firstOrNull { it.wire.equals(s, ignoreCase = true) } ?: NIGHTLY
    }
}

