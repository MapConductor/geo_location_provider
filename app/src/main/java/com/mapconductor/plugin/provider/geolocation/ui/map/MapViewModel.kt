package com.mapconductor.plugin.provider.geolocation.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel that exposes the latest LocationSample as the current device position.
 *
 * It observes StorageService.latestFlow(limit = 1) and maps it to a single LocationSample.
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    val currentLocation: StateFlow<LocationSample?> =
        StorageService.latestFlow(app.applicationContext, limit = 1)
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

