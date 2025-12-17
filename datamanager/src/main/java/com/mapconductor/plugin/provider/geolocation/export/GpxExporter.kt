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
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export helper for GPX 1.1 track logs.
 *
 * This mirrors GeoJsonExporter in responsibilities:
 * - Convert LocationSample list to GPX string.
 * - Write GPX or a ZIP containing one GPX into Downloads/GeoLocationProvider.
 */
object GpxExporter {

    private val nameFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Backward-compatible helper: auto-generates file name (".gpx", no ZIP).
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
     * @param compressAsZip true to create ZIP (containing one .gpx file), false to write .gpx directly.
     * @return MediaStore Uri, or null when creation failed.
     */
    fun exportToDownloads(
        context: Context,
        records: List<LocationSample>,
        baseName: String? = null,
        compressAsZip: Boolean = false
    ): Uri? {
        val gpx = toGpx(records).toByteArray(Charsets.UTF_8)
        val resolvedBase = baseName ?: nameFmt.format(Date())
        val displayName = if (compressAsZip) "$resolvedBase.zip" else "$resolvedBase.gpx"
        val mimeType = if (compressAsZip) "application/zip" else "application/gpx+xml"

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
            writePayload(os, gpx, resolvedBase, compressAsZip)
        } ?: return null

        return uri
    }

    private fun writePayload(
        os: OutputStream,
        gpxBytes: ByteArray,
        baseName: String,
        compressAsZip: Boolean
    ) {
        if (!compressAsZip) {
            os.write(gpxBytes)
            os.flush()
            return
        }

        // When ZIP, store a single file named baseName.gpx inside.
        ZipOutputStream(BufferedOutputStream(os)).use { zos ->
            val entry = ZipEntry("$baseName.gpx")
            zos.putNextEntry(entry)
            zos.write(gpxBytes)
            zos.closeEntry()
        }
    }

    /**
     * Convert LocationSample list to a GPX 1.1 document with a single track.
     */
    internal fun toGpx(records: List<LocationSample>): String {
        val sb = StringBuilder(1024)

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        sb.append("<gpx")
        sb.append(" version=\"1.1\"")
        sb.append(" creator=\"GeoLocationProvider\"")
        sb.append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        sb.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        sb.append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
        sb.append("http://www.topografix.com/GPX/1/1/gpx.xsd\"")
        sb.append(">")

        if (records.isNotEmpty()) {
            val first = records.first()
            if (first.timeMillis > 0L) {
                sb.append("<metadata>")
                sb.append("<time>")
                sb.append(timeFmt.format(Date(first.timeMillis)))
                sb.append("</time>")
                sb.append("</metadata>")
            }
        }

        sb.append("<trk>")
        sb.append("<name>Track</name>")
        sb.append("<trkseg>")

        records.forEach { r ->
            sb.append("<trkpt")
            sb.append(" lat=\"")
            sb.append(r.lat)
            sb.append("\" lon=\"")
            sb.append(r.lon)
            sb.append("\">")

            if (r.timeMillis > 0L) {
                sb.append("<time>")
                sb.append(timeFmt.format(Date(r.timeMillis)))
                sb.append("</time>")
            }

            sb.append("<extensions>")

            sb.append("<accuracy>")
            sb.append(r.accuracy)
            sb.append("</accuracy>")

            r.provider?.let { provider ->
                sb.append("<provider>")
                sb.appendXmlEscaped(provider)
                sb.append("</provider>")
            }

            sb.append("<battery_pct>")
            sb.append(r.batteryPercent)
            sb.append("</battery_pct>")

            sb.append("<is_charging>")
            sb.append(if (r.isCharging) "true" else "false")
            sb.append("</is_charging>")

            sb.append("<heading_deg>")
            sb.append(r.headingDeg)
            sb.append("</heading_deg>")

            r.courseDeg?.let { course ->
                sb.append("<course_deg>")
                sb.append(course)
                sb.append("</course_deg>")
            }

            sb.append("<speed_mps>")
            sb.append(r.speedMps)
            sb.append("</speed_mps>")

            sb.append("<gnss_used>")
            sb.append(r.gnssUsed)
            sb.append("</gnss_used>")

            sb.append("<gnss_total>")
            sb.append(r.gnssTotal)
            sb.append("</gnss_total>")

            sb.append("<gnss_cn0_mean>")
            sb.append(r.cn0)
            sb.append("</gnss_cn0_mean>")

            sb.append("</extensions>")
            sb.append("</trkpt>")
        }

        sb.append("</trkseg>")
        sb.append("</trk>")
        sb.append("</gpx>")

        return sb.toString()
    }
}

private fun StringBuilder.appendXmlEscaped(value: String) {
    for (ch in value) {
        when (ch) {
            '&'  -> append("&amp;")
            '<'  -> append("&lt;")
            '>'  -> append("&gt;")
            '"'  -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }
}

