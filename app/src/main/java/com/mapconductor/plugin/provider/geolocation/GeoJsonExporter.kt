package com.mapconductor.plugin.provider.geolocation

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

object GeoJsonExporter {

    /** GeoJSONの中身を生成（FeatureCollection） */
    private fun buildGeoJson(samples: List<LocationSample>): String {
        val features = JSONArray()
        samples.forEach { s ->
            val point = JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray(listOf(s.lon, s.lat))) // [lon, lat] がGeoJSON標準
            }
            val props = JSONObject().apply {
                put("accuracy", s.accuracy)
                put("provider", s.provider ?: JSONObject.NULL)
                put("batteryPct", s.batteryPct)
                put("isCharging", s.isCharging)
                put("timestamp", s.createdAt) // epoch millis
            }
            val feature = JSONObject().apply {
                put("type", "Feature")
                put("geometry", point)
                put("properties", props)
            }
            features.put(feature)
        }
        return JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", features)
        }.toString()
    }

    private fun nowStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())

    /** 2-1) アプリ専用の外部領域: /Android/data/<pkg>/files/exports/ に出力（固定ディレクトリ） */
    suspend fun exportToAppExternal(context: Context, limit: Int = 1000): Uri = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val items = db.locationSampleDao().latestList(limit)

        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, "locations-${nowStamp()}.geojson")
        file.outputStream().use { os -> os.write(buildGeoJson(items).toByteArray()) }

        Uri.fromFile(file) // アプリ内で使う想定（共有するならFileProviderを使う）
    }

    /**
     * 2-2) Downloads に出力（ユーザーが見える固定ディレクトリ）
     * Android 10+ は MediaStore で権限不要。Android 9 以下は WRITE_EXTERNAL_STORAGE が必要。
     */
    suspend fun exportToDownloads(context: Context, limit: Int = 1000): Uri = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val items = db.locationSampleDao().latestList(limit)
        val json = buildGeoJson(items)

        val resolver = context.contentResolver
        val name = "locations-${nowStamp()}.geojson"
        val relative = Environment.DIRECTORY_DOWNLOADS + "/GeoLocationProvider"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/geo+json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to create download entry")
        resolver.openOutputStream(uri).use { os: OutputStream? ->
            requireNotNull(os) { "OutputStream is null" }
            os.write(json.toByteArray())
            os.flush()
        }
        uri
    }
}
