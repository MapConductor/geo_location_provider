package com.mapconductor.plugin.provider.geolocation.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mapconductor.plugin.provider.storageservice.StorageService
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * In-memory buffer for the history list.
     *
     * - Filled from StorageService.latestFlow.
     * - Records remain in this buffer even when Room rows are deleted by upload/export,
     *   and are dropped only when the buffer exceeds [bufferLimit].
     */
    private val bufferLimit: Int = 6

    private val _items: MutableStateFlow<List<LocationSample>> =
        MutableStateFlow(emptyList())

    val items: StateFlow<List<LocationSample>> = _items

    init {
        val ctx = app.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            StorageService.latestFlow(ctx, limit = bufferLimit)
                .collect { latest ->
                    updateBuffer(latest)
                }
        }
    }

    private fun updateBuffer(latest: List<LocationSample>) {
        if (latest.isEmpty()) {
            // Do not clear the buffer when DB becomes empty (for example after upload).
            return
        }

        val comparator =
            compareByDescending<LocationSample> { it.timeMillis }
                .thenBy { providerRank(it.provider) }
                .thenByDescending { it.id }

        val current = _items.value
        if (current.isEmpty()) {
            // First non-empty emission; use it as the initial buffer.
            _items.value = latest.sortedWith(comparator)
                .take(bufferLimit)
            return
        }

        val existingIds = current.asSequence().map { it.id }.toHashSet()
        val newOnes = latest.filter { it.id !in existingIds }
        if (newOnes.isEmpty()) return

        val merged = (newOnes + current)
            .sortedWith(comparator)
            .take(bufferLimit)

        _items.value = merged
    }

    private fun providerRank(provider: String?): Int {
        val v = provider?.trim()?.lowercase(Locale.ROOT) ?: return 2
        return when (v) {
            "gps" -> 0
            "gps_corrected" -> 1
            else -> 2
        }
    }
}
