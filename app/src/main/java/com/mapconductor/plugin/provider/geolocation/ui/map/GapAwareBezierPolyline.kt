package com.mapconductor.plugin.provider.geolocation.ui.map

import com.mapconductor.core.features.GeoPointImpl
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal object GapAwareBezierPolyline {

    private const val EARTH_RADIUS_M = 6371000.0
    private const val DEFAULT_MIN_GAP_SEC = 10.0
    private const val DEFAULT_MAX_GAP_SEC = 30.0
    private const val MIN_DISTANCE_M = 5.0

    fun build(samples: List<LocationSample>): List<GeoPointImpl> {
        return build(
            samples = samples,
            minGapSec = DEFAULT_MIN_GAP_SEC,
            maxGapSec = DEFAULT_MAX_GAP_SEC
        )
    }

    fun build(
        samples: List<LocationSample>,
        minGapSec: Double,
        maxGapSec: Double
    ): List<GeoPointImpl> {
        if (samples.isEmpty()) return emptyList()

        val sorted = samples.sortedBy { it.timeMillis }
        if (sorted.size == 1) {
            val s = sorted.first()
            return listOf(GeoPointImpl.fromLatLong(s.lat, s.lon))
        }

        val result = mutableListOf<GeoPointImpl>()
        result.add(GeoPointImpl.fromLatLong(sorted.first().lat, sorted.first().lon))

        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val dtSec = (b.timeMillis - a.timeMillis).toDouble() / 1000.0

            if (dtSec.isFinite() && dtSec >= minGapSec && dtSec <= maxGapSec) {
                val segment = buildGapAwareSegment(a, b, dtSec)
                result.addAll(segment)
            } else {
                result.add(GeoPointImpl.fromLatLong(b.lat, b.lon))
            }
        }

        return result
    }

    private fun buildGapAwareSegment(
        a: LocationSample,
        b: LocationSample,
        dtSec: Double
    ): List<GeoPointImpl> {
        val distM = distanceMeters(a.lat, a.lon, b.lat, b.lon)
        if (!distM.isFinite() || distM < MIN_DISTANCE_M) {
            return listOf(GeoPointImpl.fromLatLong(b.lat, b.lon))
        }

        val fallbackBearing = bearingDeg(a.lat, a.lon, b.lat, b.lon)
        val startBearing = a.courseDeg?.takeIf { it.isFinite() } ?: fallbackBearing
        val endBearing = b.courseDeg?.takeIf { it.isFinite() } ?: fallbackBearing

        val speedFromChord = distM / dtSec
        val speedFromSamples = (a.speedMps + b.speedMps) / 2.0
        val speedEst = maxOf(speedFromChord, speedFromSamples, 0.0)

        val unconstrainedTangentM = speedEst * dtSec * 0.4
        val tangentM =
            unconstrainedTangentM
                .coerceIn(5.0, 60.0)
                .coerceAtMost(distM * 0.9 + 5.0)

        val c1 = destinationPoint(a.lat, a.lon, startBearing, tangentM)
        val c2 = destinationPoint(b.lat, b.lon, endBearing + 180.0, tangentM)

        val steps =
            (dtSec / 2.0)
                .roundToInt()
                .coerceIn(4, 15)

        val out = mutableListOf<GeoPointImpl>()
        for (step in 1..steps) {
            val t = step.toDouble() / (steps + 1).toDouble()
            val p = cubicBezier(
                p0 = LatLon(a.lat, a.lon),
                p1 = c1,
                p2 = c2,
                p3 = LatLon(b.lat, b.lon),
                t = t
            )
            if (p.lat.isFinite() && p.lon.isFinite()) {
                out.add(GeoPointImpl.fromLatLong(p.lat, p.lon))
            }
        }

        out.add(GeoPointImpl.fromLatLong(b.lat, b.lon))
        return out
    }

    private data class LatLon(
        val lat: Double,
        val lon: Double
    )

    private fun cubicBezier(
        p0: LatLon,
        p1: LatLon,
        p2: LatLon,
        p3: LatLon,
        t: Double
    ): LatLon {
        val u = 1.0 - t
        val tt = t * t
        val uu = u * u
        val uuu = uu * u
        val ttt = tt * t

        val lat =
            (uuu * p0.lat) +
                (3.0 * uu * t * p1.lat) +
                (3.0 * u * tt * p2.lat) +
                (ttt * p3.lat)
        val lon =
            (uuu * p0.lon) +
                (3.0 * uu * t * p1.lon) +
                (3.0 * u * tt * p2.lon) +
                (ttt * p3.lon)

        return LatLon(lat = lat, lon = lon)
    }

    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val lat1Rad = lat1.toRadians()
        val lat2Rad = lat2.toRadians()
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()

        val a =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_M * c
    }

    private fun bearingDeg(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val lat1Rad = lat1.toRadians()
        val lat2Rad = lat2.toRadians()
        val dLonRad = (lon2 - lon1).toRadians()

        val y = sin(dLonRad) * cos(lat2Rad)
        val x =
            cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(dLonRad)
        val brngRad = atan2(y, x)
        return (brngRad.toDegrees() + 360.0) % 360.0
    }

    private fun destinationPoint(
        lat: Double,
        lon: Double,
        bearingDeg: Double,
        distanceM: Double
    ): LatLon {
        val bearingRad = bearingDeg.toRadians()
        val latRad = lat.toRadians()
        val lonRad = lon.toRadians()

        val angDist = distanceM / EARTH_RADIUS_M

        val sinLat1 = sin(latRad)
        val cosLat1 = cos(latRad)
        val sinAng = sin(angDist)
        val cosAng = cos(angDist)

        val sinLat2 = sinLat1 * cosAng + cosLat1 * sinAng * cos(bearingRad)
        val lat2 = kotlin.math.asin(sinLat2)

        val y = sin(bearingRad) * sinAng * cosLat1
        val x = cosAng - sinLat1 * sinLat2
        val lon2 = lonRad + atan2(y, x)

        return LatLon(
            lat = lat2.toDegrees(),
            lon = ((lon2.toDegrees() + 540.0) % 360.0) - 180.0
        )
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private fun Double.toDegrees(): Double = this * 180.0 / PI
}
