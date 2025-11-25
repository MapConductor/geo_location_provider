package com.mapconductor.plugin.provider.geolocation.deadreckoning.api

import android.content.Context
import com.mapconductor.plugin.provider.deadreckoning.impl.DeadReckoningImpl

/**
 * Factory for creating DeadReckoning implementations.
 *
 * Role:
 * - Allow library users to obtain DeadReckoning without depending on concrete classes
 *   such as DeadReckoningImpl.
 * - Keep the public API stable even if internal implementation changes in the future.
 */
object DeadReckoningFactory {

    /**
     * Create a DeadReckoning implementation.
     *
     * @param context Application or Service context.
     * @param config  Configuration that controls DeadReckoning behavior.
     * @return DeadReckoning implementation instance.
     */
    fun create(context: Context, config: DeadReckoningConfig = DeadReckoningConfig()): DeadReckoning {
        val appContext = context.applicationContext
        return DeadReckoningImpl(appContext, config)
    }
}

