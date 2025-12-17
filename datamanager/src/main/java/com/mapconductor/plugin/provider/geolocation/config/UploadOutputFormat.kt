package com.mapconductor.plugin.provider.geolocation.config

/**
 * Output format for exported location logs.
 *
 * - GEOJSON : GeoJSON FeatureCollection (current default).
 * - GPX     : GPX 1.1 track log.
 */
enum class UploadOutputFormat(val wire: String) {
    GEOJSON("geojson"),
    GPX("gpx");

    companion object {
        fun fromString(s: String?): UploadOutputFormat =
            entries.firstOrNull { it.wire.equals(s, ignoreCase = true) } ?: GEOJSON
    }
}

