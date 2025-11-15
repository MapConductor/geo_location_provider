package com.mapconductor.plugin.provider.geolocation.repository

import com.mapconductor.plugin.provider.storageservice.room.LocationSample

/**
 * LocationSample の取得元を抽象化するインターフェース。
 * - dataselector は Room/DAO を知らず、このインターフェースだけに依存する。
 */
interface LocationSampleSource {

    /**
     * [fromInclusive, toExclusive) の半開区間で昇順に LocationSample を取得する。
     */
    suspend fun findBetween(
        fromInclusive: Long,
        toExclusive: Long
    ): List<LocationSample>
}
