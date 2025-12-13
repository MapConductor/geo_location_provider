package com.mapconductor.plugin.provider.geolocation.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GeoJsonExporter {

    private val nameFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * Backward-compatible helper: auto-generates file name (".geojson", no ZIP).
     */
    fun exportToDownloads(
        context: Context,
        records: List<LocationSample>
    ): Uri? {
        return exportToDownloads(
            context = context,
            records = records,
            baseName = null,
            compressAsZip = false
        )
    }

    /**
     * Export to Downloads/GeoLocationProvider.
     *
     * @param baseName      when null, file name is auto-generated as "yyyyMMdd-HHmmss".
     * @param compressAsZip true to create ZIP (containing one .geojson file), false to write .geojson directly.
     * @return MediaStore Uri, or null when creation failed.
     */
    fun exportToDownloads(
        context: Context,
        records: List<LocationSample>,
        baseName: String? = null,
        compressAsZip: Boolean = false
    ): Uri? {
        val json = toGeoJson(records).toByteArray(Charsets.UTF_8)
        val resolvedBase = baseName ?: nameFmt.format(Date())
        val displayName = if (compressAsZip) "$resolvedBase.zip" else "$resolvedBase.geojson"
        val mimeType = if (compressAsZip) "application/zip" else "application/geo+json"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= 29) {
                // Place under Downloads/GeoLocationProvider.
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/GeoLocationProvider"
                )
            }
        }

        // For API 29+, insert into Downloads; for older API, use Files(external).
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = resolver.insert(collection, values) ?: return null

        resolver.openOutputStream(uri)?.use { os ->
            writePayload(os, json, resolvedBase, compressAsZip)
        } ?: return null

        return uri
    }

    private fun writePayload(
        os: OutputStream,
        jsonBytes: ByteArray,
        baseName: String,
        compressAsZip: Boolean
    ) {
        if (!compressAsZip) {
            os.write(jsonBytes)
            os.flush()
            return
        }

        // When ZIP, store a single file named baseName.geojson inside.
        ZipOutputStream(BufferedOutputStream(os)).use { zos ->
            val entry = ZipEntry("$baseName.geojson")
            zos.putNextEntry(entry)
            zos.write(jsonBytes)
            zos.closeEntry()
        }
    }

    /** LocationSample -> GeoJSON (FeatureCollection). */
    internal fun toGeoJson(records: List<LocationSample>): String {
        val sb = StringBuilder(1024)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        records.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":{")
            sb.append("\"type\":\"Point\",\"coordinates\":[")
            sb.append(r.lon).append(',').append(r.lat) // GeoJSON uses [lon, lat]
            sb.append("]},\"properties\":{")
            // Existing properties.
            sb.append("\"accuracy\":").append(r.accuracy)
            r.provider?.let { sb.append(",\"provider\":\"").append(it).append('"') }
            sb.append(",\"battery_pct\":").append(r.batteryPercent)
            sb.append(",\"is_charging\":").append(if (r.isCharging) "true" else "false")
            sb.append(",\"created_at\":").append(r.timeMillis)

            // Additional properties (skip when null).
            r.headingDeg.let   { sb.append(",\"heading_deg\":").append(it) }
            r.courseDeg?.let   { sb.append(",\"course_deg\":").append(it) }
            r.speedMps.let     { sb.append(",\"speed_mps\":").append(it) }
            r.gnssUsed.let     { sb.append(",\"gnss_used\":").append(it) }
            r.gnssTotal.let    { sb.append(",\"gnss_total\":").append(it) }
            r.cn0.let          { sb.append(",\"gnss_cn0_mean\":").append(it) }
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }
}
