package com.mapconductor.plugin.provider.geolocation.fusion

/**
 * Process-wide registry for the GPS correction engine.
 *
 * This allows the app to provide an implementation without wiring it through
 * Android component constructors.
 */
object GpsCorrectionEngineRegistry {
    @Volatile
    private var engine: GpsCorrectionEngine = NoopGpsCorrectionEngine

    fun get(): GpsCorrectionEngine = engine

    fun register(engine: GpsCorrectionEngine) {
        this.engine = engine
    }

    fun reset() {
        engine = NoopGpsCorrectionEngine
    }
}

