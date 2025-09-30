package com.mapconductor.plugin.provider.geolocation.drive

// ★ 既存の各ファイルの import を“必要分だけ”ここへ集約
// 例:
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import com.mapconductor.plugin.provider.geolocation.drive.net.DriveHttp

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
        // & や # で区切る
        val sb = StringBuilder()
        for (c in src.substring(start)) {
            if (c == '&' || c == '#') break
            sb.append(c)
        }
        return sb.toString().ifBlank { null }
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
    private val http: OkHttpClient = DriveHttp.client() // ← defaultClient() から差し替え
) {
    companion object {
        const val BASE: String = "https://www.googleapis.com/drive/v3"
        const val BASE_FILES: String = "$BASE/files" // ★ 忘れずに
        const val BASE_UPLOAD: String = "https://www.googleapis.com/upload/drive/v3/files"
        val JSON: MediaType = "application/json; charset=UTF-8".toMediaType()
    }


    /** GET /about?fields=user(displayName,emailAddress) */
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
                        displayName = u.optString("displayName", null),
                        emailAddress = u.optString("emailAddress", null)
                    )
                } else null
                ApiResult.Success(AboutResponse(user))
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

    /**
     * GET /files/{id}?fields=id,name,mimeType&supportsAllDrives=true
     * 指定IDがフォルダか判定。
     */
    data class ValidateResult(
        val id: String,
        val name: String,
        val mimeType: String,
        val isFolder: Boolean,
        val canAddChildren: Boolean,
        val shortcutTargetId: String?
    )

    // supportsAllDrives=true は必須。
    // resourceKey があればクエリに付与する。
    // fields で capabilities / shortcutDetails も引く。
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
                val shortcutTargetId = obj.optJSONObject("shortcutDetails")?.optString("targetId")
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

    // ★ アップロード先フォルダの最終解決（ショートカット→実体、権限チェックまで）
    suspend fun resolveFolderIdForUpload(
        token: String,
        idOrUrl: String,
        resourceKey: String?
    ): ApiResult<String> {
        val firstId = DriveFolderId.extractFromUrlOrId(idOrUrl) ?: idOrUrl
        // 1st: 直接検証（必要なら resourceKey 付き）
        when (val r = validateFolder(token, firstId, resourceKey)) {
            is ApiResult.Success -> {
                val v = r.data
                // ショートカットなら targetId で再検証
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

        val url = "$BASE_UPLOAD?uploadType=multipart&supportsAllDrives=true&fields=id,name,parents,webViewLink"
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
