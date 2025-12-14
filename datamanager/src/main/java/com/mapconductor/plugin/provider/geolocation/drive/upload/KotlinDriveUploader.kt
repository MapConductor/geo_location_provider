package com.mapconductor.plugin.provider.geolocation.drive.upload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleDriveTokenProvider
import com.mapconductor.plugin.provider.geolocation.drive.net.DriveHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

internal class KotlinDriveUploader(
    private val context: Context,
    private val tokenProvider: GoogleDriveTokenProvider
) : Uploader {

    private val baseUploadUrl = "https://www.googleapis.com/upload/drive/v3/files"
    private val baseFilesUrl  = "https://www.googleapis.com/drive/v3/files"
    private val chunkSize = 256 * 1024 // 256 KiB (fits Drive requirements)

    override suspend fun upload(uri: Uri, folderId: String, fileName: String?): UploadResult =
        withContext(Dispatchers.IO) {
            val token = tokenProvider.getAccessToken()
                ?: return@withContext UploadResult.Failure(
                    code = 401, body = "Sign-in required", message = "No access token"
                )

            val cr = context.contentResolver
            val name = fileName ?: (guessName(cr, uri) ?: "payload.bin")
            val mime = guessMime(cr, uri) ?: "application/octet-stream"
            val size = tryGetSize(cr, uri)

            // Normalize folder id (URL or raw id) once.
            val parentId: String? =
                DriveFolderId.extractFromUrlOrId(folderId)?.takeIf { it.isNotBlank() }

            // Small payloads use multipart (simple upload); larger ones use resumable.
            // When size is unknown, prefer simple upload to avoid partial-chunk errors.
            val useResumable = size != null && size > 2L * 1024 * 1024 // >2MB threshold.

            return@withContext if (!useResumable) {
                simpleUpload(token, cr, uri, name, mime, parentId)
            } else {
                resumableUpload(token, cr, uri, name, mime, parentId, size)
            }
        }

    // -------------------- simple upload (multipart) --------------------

    private fun simpleUpload(
        token: String,
        cr: ContentResolver,
        uri: Uri,
        name: String,
        mime: String,
        folderId: String?
    ): UploadResult {
        val boundary = "boundary_${System.currentTimeMillis()}"

        val meta = JSONObject().apply {
            put("name", name)
            put("mimeType", mime)
            folderId?.let { put("parents", listOf(it)) }
        }.toString()

        val body = object : RequestBody() {
            override fun contentType(): MediaType? =
                "multipart/related; boundary=$boundary".toMediaType()

            override fun writeTo(sink: BufferedSink) {
                // part1: metadata
                sink.writeUtf8("--$boundary\r\n")
                sink.writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                sink.writeUtf8(meta)
                sink.writeUtf8("\r\n")

                // part2: media
                sink.writeUtf8("--$boundary\r\n")
                sink.writeUtf8("Content-Type: $mime\r\n\r\n")

                cr.openInputStream(uri)?.use { input ->
                    sink.writeAll(input.source())
                } ?: throw IOException("openInputStream failed")

                sink.writeUtf8("\r\n--$boundary--\r\n")
            }
        }

        val client = DriveHttp.client().newBuilder()
            .addInterceptor(DriveHttp.auth(token))
            .build()

        val req = Request.Builder()
            .url("$baseUploadUrl?uploadType=multipart&fields=id,name,webViewLink")
            .post(body)
            .build()

        return DriveHttp.withBackoff {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && DriveHttp.shouldRetry(resp.code)) {
                    throw IOException("HTTP ${resp.code}")
                }
                if (!resp.isSuccessful) {
                    return@use UploadResult.Failure(
                        code = resp.code,
                        body = resp.body?.string().orEmpty(),
                        message = "Drive simple upload failed"
                    )
                }

                val jo = JSONObject(resp.body?.string().orEmpty())
                val idVal   = jo.optString("id", "")
                val nameVal = jo.optString("name", "")
                val linkVal = jo.optString("webViewLink").takeIf { it.isNotBlank() }
                UploadResult.Success(
                    id = idVal,
                    name = nameVal,
                    webViewLink = linkVal ?: ""
                )
            }
        }
    }

    // -------------------- resumable upload --------------------

    private fun resumableUpload(
        token: String,
        cr: ContentResolver,
        uri: Uri,
        name: String,
        mime: String,
        folderId: String?,
        totalSize: Long?
    ): UploadResult {
        val client = DriveHttp.client().newBuilder()
            .addInterceptor(DriveHttp.auth(token))
            .build()

        val sessionUrl = startSession(client, name, mime, folderId, totalSize)

        var finalId: String? = null
        var finalName: String? = null
        var finalLink: String? = null

        var sent = 0L
        if (totalSize != null && totalSize > 0L) {
            // Query how many bytes have already been accepted when resuming.
            sent = queryProgress(client, sessionUrl, totalSize)
        }

        cr.openInputStream(uri)?.use { input ->
            // Skip bytes that have already been sent.
            if (sent > 0) input.skip(sent)

            var offset = sent
            val buf = ByteArray(chunkSize)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break

                val endExclusive = offset + read
                val rangeHeader = "bytes $offset-${endExclusive - 1}/${totalSize ?: "*"}"

                val req = Request.Builder()
                    .url(sessionUrl)
                    .addHeader("Content-Type", mime)
                    .addHeader("Content-Length", read.toString())
                    .addHeader("Content-Range", rangeHeader)
                    .put(buf.copyOf(read).toRequestBody())
                    .build()

                val ok = DriveHttp.withBackoff {
                    client.newCall(req).execute().use { resp ->
                        when {
                            resp.code == 308 -> {
                                // Incomplete; Range or Location header indicates progress.
                                true
                            }
                            resp.isSuccessful -> {
                                // Completed (200/201). If possible, parse JSON body.
                                val bodyStr = resp.body?.string().orEmpty()
                                if (bodyStr.isNotEmpty()) {
                                    runCatching {
                                        val jo = JSONObject(bodyStr)
                                        finalId = jo.optString("id").takeIf { it.isNotBlank() }
                                        finalName = jo.optString("name").takeIf { it.isNotBlank() }
                                        finalLink = jo.optString("webViewLink").takeIf { it.isNotBlank() }
                                    }
                                }
                                true
                            }
                            DriveHttp.shouldRetry(resp.code) -> throw IOException("HTTP ${resp.code}")
                            else -> {
                                // Error response body is used for notification.
                                throw IOException("Upload failed: ${resp.code} ${resp.body?.string()}")
                            }
                        }
                    }
                }

                if (!ok) return UploadResult.Failure(code = 500, body = "unknown failure")
                offset = endExclusive
            }
        } ?: return UploadResult.Failure(code = 400, body = "openInputStream failed")

        // Completed: if final id/name/link was returned, use it.
        if (finalId != null) {
            return UploadResult.Success(
                id = finalId!!,
                name = (finalName ?: name),
                webViewLink = (finalLink ?: "")
            )
        }

        // Fallback search: narrow by parent folder to avoid mismatches.
        val qName = name.replace("'", "\\'")
        val query = buildString {
            append("name='").append(qName).append("' and trashed=false")
            if (!folderId.isNullOrBlank()) append(" and '").append(folderId).append("' in parents")
        }
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())

        val getReq = Request.Builder()
            .url("$baseFilesUrl?q=$encoded&fields=files(id,name,webViewLink)")
            .get()
            .build()

        return DriveHttp.withBackoff {
            client.newCall(getReq).execute().use { resp ->
                if (!resp.isSuccessful && DriveHttp.shouldRetry(resp.code)) {
                    throw IOException("HTTP ${resp.code}")
                }
                if (!resp.isSuccessful) {
                    // ID is unknown, but treat upload as completed.
                    return@use UploadResult.Success(
                        id = "drive:completed",
                        name = name,
                        webViewLink = ""
                    )
                }
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                val obj = arr?.optJSONObject(0)
                val id  = obj?.optString("id")?.takeIf { it.isNotBlank() }
                val nm  = obj?.optString("name")?.takeIf { it.isNotBlank() } ?: name
                val lnk = obj?.optString("webViewLink")?.takeIf { it.isNotBlank() }
                UploadResult.Success(
                    id = id ?: "drive:completed",
                    name = nm,
                    webViewLink = (lnk ?: "")
                )
            }
        }
    }

    private fun startSession(
        client: OkHttpClient,
        name: String,
        mime: String,
        folderId: String?,
        totalSize: Long?
    ): String {
        val meta = JSONObject().apply {
            put("name", name)
            put("mimeType", mime)
            folderId?.let { put("parents", listOf(it.toString())) }
        }.toString()

        val req = Request.Builder()
            .url("$baseUploadUrl?uploadType=resumable&supportsAllDrives=true")
            .addHeader("X-Upload-Content-Type", mime)
            .apply { if (totalSize != null) addHeader("X-Upload-Content-Length", totalSize.toString()) }
            .post(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        return DriveHttp.withBackoff {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && DriveHttp.shouldRetry(resp.code)) {
                    throw IOException("HTTP ${resp.code}")
                }
                if (!resp.isSuccessful) {
                    throw IOException("startSession failed: ${resp.code} ${resp.body?.string()}")
                }
                resp.header("Location") ?: throw IOException("No Location header")
            }
        }
    }

    /** Query current received bytes for resumable upload (RFC 7233 style). */
    private fun queryProgress(client: OkHttpClient, sessionUrl: String, total: Long): Long {
        val probe = Request.Builder()
            .url(sessionUrl)
            .addHeader("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()

        return DriveHttp.withBackoff {
            client.newCall(probe).execute().use { resp ->
                if (resp.code == 308) {
                    // Example: Range: bytes=0-524287
                    val range = resp.header("Range")
                    val end = range?.substringAfter('=')?.substringAfter('-')?.toLongOrNull()
                    if (end != null) end + 1 else 0L
                } else if (resp.isSuccessful) {
                    total // Already completed.
                } else if (DriveHttp.shouldRetry(resp.code)) {
                    throw IOException("HTTP ${resp.code}")
                } else {
                    0L // No information; start from beginning.
                }
            }
        }
    }

    // -------------------- util --------------------

    private fun guessName(cr: ContentResolver, uri: Uri): String? =
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun guessMime(cr: ContentResolver, uri: Uri): String? = cr.getType(uri)

    private fun tryGetSize(cr: ContentResolver, uri: Uri): Long? {
        val fromQuery = cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (fromQuery != null) return fromQuery

        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path
            if (!path.isNullOrEmpty()) {
                val len = File(path).length()
                if (len > 0L) return len
            }
        }

        return null
    }
}
