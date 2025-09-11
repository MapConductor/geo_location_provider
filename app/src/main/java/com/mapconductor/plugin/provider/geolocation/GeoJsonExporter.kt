package com.mapconductor.plugin.provider.geolocation

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

object GeoJsonExporter {

    private val nameFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** LocationSample -> GeoJSON(FeatureCollection) */
    private fun toGeoJson(records: List<LocationSample>): String {
        val sb = StringBuilder(1024)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        records.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":{")
            sb.append("\"type\":\"Point\",\"coordinates\":[")
            sb.append(r.lon).append(',').append(r.lat) // GeoJSONは [lon, lat]
            sb.append("]},\"properties\":{")
            sb.append("\"timestamp\":").append(r.createdAt)
            sb.append(",\"accuracy\":").append(r.accuracy)
            sb.append(",\"batteryPct\":").append(r.batteryPct)
            sb.append(",\"isCharging\":").append(if (r.isCharging) "true" else "false")
            sb.append(",\"provider\":")
            if (r.provider != null) sb.append("\"").append(r.provider).append("\"") else sb.append("null")
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Downloads/GeoLocationProvider に保存。成功時 Uri、データ無し/失敗時 null */
    suspend fun exportToDownloads(context: Context, records: List<LocationSample>): Uri? =
        withContext(Dispatchers.IO) {
            if (records.isEmpty()) return@withContext null

            val fileName = "locations-${nameFmt.format(Date())}.geojson"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/geo+json")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/GeoLocationProvider"
                    )
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out: OutputStream ->
                    out.bufferedWriter(Charsets.UTF_8).use { it.write(toGeoJson(records)) }
                }
                uri
            } catch (_: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
}
