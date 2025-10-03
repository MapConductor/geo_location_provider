package com.mapconductor.plugin.provider.geolocation.core.domain.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample
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
     * 既存互換：ファイル名は内部で自動生成（.geojson、非ZIP）
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
     * Downloads/GeoLocationProvider へエクスポート。
     *
     * @param baseName    null の場合は "yyyyMMdd-HHmmss" で自動生成
     * @param compressAsZip true で ZIP（中身は .geojson 1ファイル）、false で .geojson 直書き
     * @return MediaStore の Uri（作成失敗時は null）
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
                // Downloads/GeoLocationProvider に配置
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/GeoLocationProvider"
                )
            }
        }

        // API29+ は Downloads、旧API は Files(external) に挿入
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

        // ZIP 内に baseName.geojson という1ファイルで格納
        ZipOutputStream(BufferedOutputStream(os)).use { zos ->
            val entry = ZipEntry("$baseName.geojson")
            zos.putNextEntry(entry)
            zos.write(jsonBytes)
            zos.closeEntry()
        }
    }

    /** LocationSample -> GeoJSON(FeatureCollection) */
    private fun toGeoJson(records: List<LocationSample>): String {
        val sb = StringBuilder(1024)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        records.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":{")
            sb.append("\"type\":\"Point\",\"coordinates\":[")
            sb.append(r.lon).append(',').append(r.lat) // GeoJSON は [lon, lat]
            sb.append("]},\"properties\":{")
            // 既存プロパティ
            sb.append("\"accuracy\":").append(r.accuracy)
            r.provider?.let { sb.append(",\"provider\":\"").append(it).append('"') }
            sb.append(",\"battery_pct\":").append(r.batteryPct)
            sb.append(",\"is_charging\":").append(if (r.isCharging) "true" else "false")
            sb.append(",\"created_at\":").append(r.createdAt)

            // ▼ 追加プロパティ（null は出力しない）
            r.headingDeg?.let   { sb.append(",\"heading_deg\":").append(it) }
            r.courseDeg?.let    { sb.append(",\"course_deg\":").append(it) }
            r.speedMps?.let     { sb.append(",\"speed_mps\":").append(it) }
            r.gnssUsed?.let     { sb.append(",\"gnss_used\":").append(it) }
            r.gnssTotal?.let    { sb.append(",\"gnss_total\":").append(it) }
            r.gnssCn0Mean?.let  { sb.append(",\"gnss_cn0_mean\":").append(it) }

            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }

}