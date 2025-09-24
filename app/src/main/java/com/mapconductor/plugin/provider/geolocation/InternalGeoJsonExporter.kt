package com.mapconductor.plugin.provider.geolocation

import android.content.Context
import android.util.Log
import com.mapconductor.plugin.provider.geolocation.core.data.room.LocationSample
import com.mapconductor.plugin.provider.geolocation.util.LogTags
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 自動（定時）用の内部一時エクスポータ。
 * - 出力先: context.cacheDir/exports/
 * - 返り値: 生成したファイル (null の場合は失敗)
 * - 圧縮: デフォルト true（.geojson を zip に格納）
 *
 * 注意:
 * - Upload 処理は別フェーズで実装。ここでは「ファイルを作るだけ」。
 * - DB 削除やファイル削除は呼び出し側のポリシーで行う。
 */
object InternalGeoJsonExporter {

    private val nameFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** LocationSample -> GeoJSON(FeatureCollection) */
    private fun toGeoJson(records: List<LocationSample>): String {
        // 依存最小化のため、使用フィールドは lat/lon のみとする
        val sb = StringBuilder(1024)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        records.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"geometry\":{")
            sb.append("\"type\":\"Point\",\"coordinates\":[")
            sb.append(r.lon).append(',').append(r.lat) // GeoJSON は [lon, lat]
            sb.append("]}")
            // properties は最小構成（必要なら後続フェーズで拡張）
            sb.append(",\"properties\":{")
            sb.append("\"source\":\"GeoLocationProvider\"")
            sb.append("}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 内部一時: /cache/exports/ へ GeoJSON を作成。
     * @param compress true なら .zip に格納（エントリ名は locations.geojson）
     */
    fun writeGeoJsonToCache(
        context: Context,
        records: List<LocationSample>,
        compress: Boolean = true
    ): File? {
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val baseName = "locations-${nameFmt.format(System.currentTimeMillis())}"

            if (compress) {
                val outFile = File(dir, "$baseName.geojson.zip")
                val geojson = toGeoJson(records)
                ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                    zos.putNextEntry(ZipEntry("locations.geojson"))
                    zos.write(geojson.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                Log.i(LogTags.WORKER, "Internal export (zip) created: ${outFile.absolutePath}")
                outFile
            } else {
                val outFile = File(dir, "$baseName.geojson")
                FileOutputStream(outFile).use { fos ->
                    fos.write(toGeoJson(records).toByteArray(Charsets.UTF_8))
                }
                Log.i(LogTags.WORKER, "Internal export (plain) created: ${outFile.absolutePath}")
                outFile
            }
        } catch (e: Throwable) {
            Log.e(LogTags.WORKER, "Internal export failed", e)
            null
        }
    }

    /**
     * 古い一時ファイルの掃除（任意で呼び出し）。
     * @param days 保持日数。既定 7 日より古いものを削除。
     */
    fun cleanupOldTempFiles(context: Context, days: Int = 7) {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                runCatching { f.delete() }.onSuccess {
                    Log.i(LogTags.WORKER, "Deleted old temp file: ${f.name}")
                }
            }
        }
    }
}
