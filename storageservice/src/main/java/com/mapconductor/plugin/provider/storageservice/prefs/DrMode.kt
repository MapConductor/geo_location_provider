package com.mapconductor.plugin.provider.storageservice.prefs

/**
 * Dead reckoning mode used by GeoLocationService.
 *
 * - Prediction: insert DR samples in real time between GPS fixes.
 * - Completion: use DR only to fill gaps between GPS fixes using backfilled samples.
 */
enum class DrMode {
    Prediction,
    Completion
}

