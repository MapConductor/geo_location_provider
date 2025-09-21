package com.mapconductor.plugin.provider.geolocation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class ApiResult<out T> {
    data class Success<T>(val data: T, val code: Int) : ApiResult<T>()
    data class HttpError(val code: Int, val body: String?) : ApiResult<Nothing>()
    data class NetworkError(val exception: IOException) : ApiResult<Nothing>()
}

private fun sanitizeToken(raw: String): String {
    // 最初の1行だけ採用 & trim、"Bearer " が付いてたら剥がす
    val oneLine = raw.trim().lineSequence().firstOrNull()?.trim().orEmpty()
    val noBearer = oneLine.removePrefix("Bearer ").trim()
    return noBearer
}

// 既出の sanitizeToken() とクラス定義はそのまま

class DriveApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
            redactHeader("Authorization")
        })
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val base = "https://www.googleapis.com/drive/v3"

    // ★ ここを IO に切り替え
    suspend fun aboutGet(token: String): ApiResult<AboutResponse> = withContext(Dispatchers.IO) {
        try {
            val tok = sanitizeToken(token)
            val req = Request.Builder()
                .url("$base/about?fields=user,storageQuota")
                .header("Authorization", "Bearer $tok")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && body != null) {
                    ApiResult.Success(json.decodeFromString(AboutResponse.serializer(), body), resp.code)
                } else {
                    ApiResult.HttpError(resp.code, body)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        } catch (t: Throwable) {
            ApiResult.HttpError(-1, "${t::class.java.simpleName}: ${t.message}")
        }
    }

    // ★ こちらも IO に切り替え
    suspend fun validateFolder(token: String, folderId: String): ApiResult<DriveFile> = withContext(Dispatchers.IO) {
        try {
            val tok = sanitizeToken(token)
            val fields = "id,name,mimeType,capabilities(canAddChildren)"
            val req = Request.Builder()
                .url("$base/files/$folderId?fields=$fields")
                .header("Authorization", "Bearer $tok")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && body != null) {
                    ApiResult.Success(json.decodeFromString(DriveFile.serializer(), body), resp.code)
                } else {
                    ApiResult.HttpError(resp.code, body)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        } catch (t: Throwable) {
            ApiResult.HttpError(-1, "${t::class.java.simpleName}: ${t.message}")
        }
    }
}

@Serializable
data class AboutResponse(
    val user: AboutUser? = null,
    val storageQuota: StorageQuota? = null
)

@Serializable
data class AboutUser(
    val displayName: String? = null,
    val emailAddress: String? = null
)

@Serializable
data class StorageQuota(
    val limit: String? = null,
    val usage: String? = null
)

@Serializable
data class DriveFile(
    val id: String,
    val name: String? = null,
    val mimeType: String? = null,
    val capabilities: Capabilities? = null
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

@Serializable
data class Capabilities(
    @SerialName("canAddChildren") val canAddChildren: Boolean? = null
)
