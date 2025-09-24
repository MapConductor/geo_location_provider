package com.mapconductor.plugin.provider.geolocation.drive

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Google Drive REST (v3) の軽量クライアント（モデルは別ファイル定義） */
class DriveApiClient(
    private val context: Context,
    private val http: OkHttpClient = defaultClient()
) {
    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val BASE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .build()
    }

    /** GET /about?fields=user(displayName,emailAddress) */
    fun aboutGet(token: String): ApiResult<AboutResponse> = try {
        val url = "$BASE/about?fields=user(displayName,emailAddress)"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return ApiResult.HttpError(resp.code, body)
            val obj = JSONObject(body)
            val u = obj.optJSONObject("user")
            val user = if (u != null) {
                AboutResponse.User(
                    displayName = u.optString("displayName", null),
                    emailAddress = u.optString("emailAddress", null)
                )
            } else null
            ApiResult.Success(AboutResponse(user))
        }
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    }

    /**
     * GET /files/{id}?fields=id,name,mimeType&supportsAllDrives=true
     * 指定IDがフォルダか判定。
     */
    fun validateFolder(token: String, fileId: String): ApiResult<FolderInfo> = try {
        val url = "$BASE/files/$fileId?fields=id,name,mimeType&supportsAllDrives=true"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return ApiResult.HttpError(resp.code, body)
            val obj = JSONObject(body)
            ApiResult.Success(
                FolderInfo(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    mimeType = obj.optString("mimeType", "")
                )
            )
        }
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    }

    /** multipart/related で Drive へアップロード（P6用）。 */
    fun uploadMultipart(
        token: String,
        uri: Uri,
        fileName: String? = null,
        folderId: String? = null
    ): UploadResult {
        val cr: ContentResolver = context.contentResolver
        val name = fileName ?: queryDisplayName(cr, uri) ?: "export.dat"
        val mime = detectMime(cr, uri, name)
        val len  = querySize(cr, uri) ?: -1L

        // part1: metadata (application/json)
        val metaJson = JSONObject().apply {
            put("name", name)
            if (!folderId.isNullOrBlank()) put("parents", listOf(folderId))
        }.toString()
        val metaPart = MultipartBody.Part.create(
            Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
            metaJson.toRequestBody(JSON)
        )

        // part2: media (file stream)
        val mediaType = mime.toMediaType()
        val mediaBody = InputStreamRequestBody(mediaType, cr, uri, len)
        val mediaPart = MultipartBody.Part.create(
            Headers.headersOf("Content-Type", mime),
            mediaBody
        )

        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metaPart)
            .addPart(mediaPart)
            .build()

        val url = "$BASE_UPLOAD?uploadType=multipart&fields=id,name,parents,webViewLink"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .post(multipart)
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return UploadResult.Failure(resp.code, body)
                val obj = JSONObject(body)
                UploadResult.Success(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", name),
                    webViewLink = obj.optString("webViewLink", "")
                )
            }
        } catch (e: IOException) {
            UploadResult.Failure(-1, e.message ?: "network error")
        }
    }

    // ---- helpers ----
    private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? =
        cr.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun querySize(cr: ContentResolver, uri: Uri): Long? =
        cr.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    private fun detectMime(cr: ContentResolver, uri: Uri, fileName: String): String {
        cr.getType(uri)?.let { return it }
        return when {
            fileName.endsWith(".zip", true)     -> "application/zip"
            fileName.endsWith(".geojson", true) -> "application/geo+json"
            fileName.endsWith(".json", true)    -> "application/json"
            else                                -> "application/octet-stream"
        }
    }
}
