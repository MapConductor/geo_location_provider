package com.mapconductor.plugin.provider.geolocation.fusion

import android.content.Context

/**
 * Convenience entry point for installing IMS EKF GPS correction.
 *
 * Library users:
 * - Call install() once (for example from Application.onCreate()).
 * - Configure behavior via ImsEkfConfigRepository.set(...).
 *
 * The default config has enabled=false, so install() alone is safe.
 */
object ImsEkf {
    fun install(context: Context) {
        val appContext = context.applicationContext
        GpsCorrectionEngineRegistry.register(
            ImsEkfCorrectionEngine(appContext) { ImsEkfConfigRepository.get(appContext) }
        )
    }

    fun uninstall() {
        val current = GpsCorrectionEngineRegistry.get()
        if (current is LifecycleAwareGpsCorrectionEngine) {
            current.stop()
        }
        GpsCorrectionEngineRegistry.reset()
    }
}

