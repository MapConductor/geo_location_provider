package com.mapconductor.plugin.provider.geolocation.drive

// ★ 既存の各ファイルの import を“必要分だけ”ここへ集約
// 例:
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.datastore.core.IOException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ...既存の DriveApiClient / DriveFolderId / InputStreamRequestBody / UploadResult で使っていた import...

/* ===========================================
   ここに “既存クラス本体をそのまま” 同居させます
   =========================================== */
// 汎用API結果
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class HttpError(val code: Int, val body: String) : ApiResult<Nothing>()
    data class NetworkError(val exception: IOException) : ApiResult<Nothing>()
}

// About.get のモデル
data class AboutResponse(val user: User?) {
    data class User(val displayName: String?, val emailAddress: String?)
}

// ValidateFolder のモデル
data class FolderInfo(val id: String, val name: String, val mimeType: String) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

// A) sealed class UploadResult（既に別ファイルなら本体をコピー）
sealed class UploadResult {
    /** HTTP 2xx 成功 */
    data class Success(
        val id: String,
        val name: String,
        val webViewLink: String
    ) : UploadResult()

    /** 失敗（HTTP非2xxや例外） */
    data class Failure(
        val code: Int,
        val body: String,
        val message: String? = null   // ★ 追加：任意メッセージ
    ) : UploadResult()
}

// B) object DriveFolderId（既存の抽出ロジックをコピペ）
object DriveFolderId {
    fun extractFromUrlOrId(input: String?): String? {
        if (input.isNullOrBlank()) return null
        // すでにIDだけの場合を許容
        if (!input.contains("http")) return input.trim()

        val regex = Regex("""/folders/([a-zA-Z0-9_-]+)""")
        val m = regex.find(input) ?: return null
        return input?.trim()?.takeIf { it.isNotEmpty() } // ダミー：既存の本実装に置換
    }
}

/** ストリーミングでファイルを送る RequestBody */
class InputStreamRequestBody(
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

/** Google Drive REST (v3) の軽量クライアント（multipart/related アップロードのみ使用） */
class DriveApiClient(
    private val context: Context,
    private val http: OkHttpClient = defaultClient()
) {
    companion object {
        const val BASE: String = "https://www.googleapis.com/drive/v3"   // ★追加
        const val BASE_UPLOAD: String = "https://www.googleapis.com/upload/drive/v3/files"
        val JSON: MediaType = "application/json; charset=UTF-8".toMediaType()

        fun defaultClient(): OkHttpClient =
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
    /** multipart/related で Drive へアップロード */
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
        val metaJson = org.json.JSONObject().apply {
            put("name", name)
            if (!folderId.isNullOrBlank()) put("parents", listOf(folderId))
        }.toString()
        val metaPart = MultipartBody.Part.create(
            Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
            metaJson.toRequestBody(JSON)
        )

        // part2: media (file stream)  ※ InputStreamRequestBody は DriveApi.kt 内の定義に合わせる
        val mediaType = mime.toMediaType()
        val mediaBody = InputStreamRequestBody(
            mediaType = mediaType,
            contentResolver = cr,
            uri = uri,
            contentLength = len
        )
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
                if (!resp.isSuccessful) {
                    return UploadResult.Failure(resp.code, body, "HTTP ${resp.code}")
                }
                val obj = org.json.JSONObject(body)
                UploadResult.Success(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", name),
                    webViewLink = obj.optString("webViewLink", "")
                )
            }
        } catch (e: java.io.IOException) {
            UploadResult.Failure(-1, e.message ?: "network error", "IO")
        }
    }

    // ---- helpers ----
    private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? =
        cr.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun querySize(cr: ContentResolver, uri: Uri): Long? =
        cr.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

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
