package com.mapconductor.plugin.provider.geolocation.drive

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mapconductor.plugin.provider.geolocation.drive.net.DriveHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException

// Google Drive REST API v3 向けの軽量クライアントと関連モデル

// 汎用 API 結果
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class HttpError(val code: Int, val body: String) : ApiResult<Nothing>()
    data class NetworkError(val exception: IOException) : ApiResult<Nothing>()
}

data class UploadResponse(
    val id: String?,
    val name: String?,
    val mimeType: String?,
    val webViewLink: String?
)

// About.get のモデル
data class AboutResponse(val user: User?) {
    data class User(val displayName: String?, val emailAddress: String?)
}

// ValidateFolder のモデル
data class FolderInfo(val id: String, val name: String, val mimeType: String) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

// アップロード結果
sealed class UploadResult {
    /** HTTP 2xx 成功 */
    data class Success(
        val id: String,
        val name: String,
        val webViewLink: String
    ) : UploadResult()

    /** 失敗（HTTP 非 2xx やネットワーク例外） */
    data class Failure(
        val code: Int,
        val body: String,
        val message: String? = null
    ) : UploadResult()
}

// フォルダ ID 抽出ユーティリティ
object DriveFolderId {
    private val patterns = listOf(
        Regex("""/folders/([A-Za-z0-9_-]{10,})"""),
        Regex("""[?&]id=([A-Za-z0-9_-]{10,})"""),
        Regex("""/drive/folders/([A-Za-z0-9_-]{10,})""")
    )

    fun extractFromUrlOrId(src: String): String? {
        val s = src.trim().orEmpty()
        if (s.isEmpty()) return null
        if (!s.contains("http")) return s
        for (p in patterns) {
            p.find(s)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    fun extractResourceKey(src: String): String? {
        val lower = src.lowercase()
        val keyParam = "resourcekey="
        val i = lower.indexOf(keyParam)
        if (i < 0) return null
        val start = i + keyParam.length
        val sb = StringBuilder()
        for (c in src.substring(start)) {
            if (c == '&' || c == '#') break
            sb.append(c)
        }
        return sb.toString().ifBlank { null }
    }
}

/** ストリーミングでファイルを送る RequestBody */
internal class InputStreamRequestBody(
    private val mediaType: MediaType,
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentLength: Long = -1L
) : RequestBody() {
    override fun contentType(): MediaType = mediaType
    override fun contentLength(): Long = if (contentLength >= 0) contentLength else super.contentLength()
    override fun writeTo(sink: BufferedSink) {
        contentResolver.openInputStream(uri)?.use { input ->
            input.source().use { source -> sink.writeAll(source) }
        } ?: throw IllegalStateException("Unable to open input stream for $uri")
    }
}

/** Google Drive REST (v3) の軽量クライアント（multipart/related アップロードなどを提供） */
class DriveApiClient(
    private val context: Context,
    private val http: OkHttpClient = DriveHttp.client()
) {
    companion object {
        const val BASE: String = "https://www.googleapis.com/drive/v3"
        const val BASE_FILES: String = "$BASE/files"
        const val BASE_UPLOAD: String = "https://www.googleapis.com/upload/drive/v3/files"
        val JSON: MediaType = "application/json; charset=UTF-8".toMediaType()
    }

    /** GET /about?fields=user(displayName,emailAddress) を呼び出す */
    suspend fun aboutGet(token: String): ApiResult<AboutResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE/about?fields=user(displayName,emailAddress)"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext ApiResult.HttpError(resp.code, body)
                }
                val obj = JSONObject(body)
                val u = obj.optJSONObject("user")
                val user = if (u != null) {
                    AboutResponse.User(
                        displayName = u.optStringOrNull("displayName"),
                        emailAddress = u.optStringOrNull("emailAddress")
                    )
                } else null
                ApiResult.Success(AboutResponse(user))
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

    /**
     * GET /files/{id}?fields=id,name,mimeType,capabilities/canAddChildren,shortcutDetails/targetId
     * でフォルダかどうか・書き込み可能かなどを検証する。
     */
    data class ValidateResult(
        val id: String,
        val name: String,
        val mimeType: String,
        val isFolder: Boolean,
        val canAddChildren: Boolean,
        val shortcutTargetId: String?
    )

    suspend fun validateFolder(
        token: String,
        id: String,
        resourceKey: String?
    ): ApiResult<ValidateResult> = withContext(Dispatchers.IO) {
        try {
            val fields = "id,name,mimeType,capabilities/canAddChildren,shortcutDetails/targetId"
            val rk = if (!resourceKey.isNullOrBlank()) "&resourceKey=$resourceKey" else ""
            val url = "$BASE_FILES/$id?supportsAllDrives=true&fields=$fields$rk"

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext ApiResult.HttpError(resp.code, body)

                val obj = JSONObject(body)
                val mime = obj.optString("mimeType", "")
                val isFolder = (mime == "application/vnd.google-apps.folder")
                val shortcutTargetId = obj.optJSONObject("shortcutDetails")?.optStringOrNull("targetId")
                val canAdd = obj.optJSONObject("capabilities")?.optBoolean("canAddChildren", false) ?: false

                ApiResult.Success(
                    ValidateResult(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        mimeType = mime,
                        isFolder = isFolder,
                        canAddChildren = canAdd,
                        shortcutTargetId = shortcutTargetId
                    )
                )
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

    /**
     * アップロード対象のフォルダ ID を解決するヘルパー。
     * - URL が渡された場合は ID を抽出する
     * - ショートカットの場合は実体フォルダを検証する
     * - 書き込み不可の場合は HttpError を返す
     */
    suspend fun resolveFolderIdForUpload(
        token: String,
        idOrUrl: String,
        resourceKey: String?
    ): ApiResult<String> {
        val firstId = DriveFolderId.extractFromUrlOrId(idOrUrl) ?: idOrUrl
        when (val r = validateFolder(token, firstId, resourceKey)) {
            is ApiResult.Success -> {
                val v = r.data
                val final = if (v.mimeType == "application/vnd.google-apps.shortcut" && !v.shortcutTargetId.isNullOrBlank()) {
                    when (val r2 = validateFolder(token, v.shortcutTargetId!!, null)) {
                        is ApiResult.Success -> r2.data
                        is ApiResult.HttpError -> return r2
                        is ApiResult.NetworkError -> return r2
                    }
                } else v

                if (!final.isFolder) {
                    return ApiResult.HttpError(400, "Not a folder: ${final.mimeType}")
                }
                if (!final.canAddChildren) {
                    return ApiResult.HttpError(403, "No write permission for this folder")
                }
                return ApiResult.Success(final.id)
            }
            is ApiResult.HttpError -> return r
            is ApiResult.NetworkError -> return r
        }
    }

    /**
     * Drive の multipart/related アップロードを行う。
     * - 各 Part の Header に "Content-Type" を重ねて指定しない
     * - メディアパートの MIME タイプは RequestBody.contentType() で指定する
     * - multipart 全体の Content-Type は "multipart/related"
     */
    suspend fun uploadMultipart(
        token: String,
        name: String,
        parentsId: String,
        mimeType: String?,
        bytes: ByteArray
    ): ApiResult<UploadResponse> = withContext(Dispatchers.IO) {
        try {
            val metadataJson = """
            {
              "name": ${jsonEscape(name)},
              "parents": ["$parentsId"]
            }
            """.trimIndent()
            val metadataBody: RequestBody =
                metadataJson.toRequestBody("application/json; charset=utf-8".toMediaType())

            val mediaType = (mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream").toMediaType()
            val mediaBody: RequestBody = bytes.toRequestBody(mediaType)

            val multi: MultipartBody = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadataBody)
                .addPart(mediaBody)
                .build()

            val req = Request.Builder()
                .url(
                    "$BASE_UPLOAD" +
                            "?uploadType=multipart" +
                            "&supportsAllDrives=true" +
                            "&fields=id,name,mimeType,webViewLink"
                )
                .addHeader("Authorization", "Bearer $token")
                .post(multi)
                .build()

            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val parsed = parseUploadResponse(body)
                    return@withContext ApiResult.Success(parsed)
                } else {
                    return@withContext ApiResult.HttpError(code, body)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

    private fun parseUploadResponse(json: String): UploadResponse {
        val obj = JSONObject(json)
        return UploadResponse(
            id = obj.optStringOrNull("id"),
            name = obj.optStringOrNull("name"),
            mimeType = obj.optStringOrNull("mimeType"),
            webViewLink = obj.optStringOrNull("webViewLink")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val s = optString(key)
        return if (s.isBlank()) null else s
    }

    private fun jsonEscape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? =
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun querySize(cr: ContentResolver, uri: Uri): Long? =
        cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
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
