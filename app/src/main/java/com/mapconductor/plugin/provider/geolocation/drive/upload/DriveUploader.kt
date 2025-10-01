package com.mapconductor.plugin.provider.geolocation.drive.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.util.LogTags
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** アップロード方式の共通境界。URI を受け取り、結果を UploadResult で返す。*/
interface Uploader {
    suspend fun upload(
        uri: Uri,
        folderId: String,
        fileName: String? = null
    ): UploadResult
}

/**
 * 既存の DriveApiClient + GoogleAuthRepository を内部で利用する薄いラッパ。
 * ※ Resumable対応の実体（OkHttp直叩き版）KotlinDriveUploader とクラス名が衝突するため改名。
 */
class ApiClientDriveUploader(
    private val appContext: Context,
    private val client: DriveApiClient = DriveApiClient(appContext),
    private val auth: GoogleAuthRepository = GoogleAuthRepository(appContext)
) : Uploader {

    // resourceKey を読むための prefs
    private val prefs by lazy { DrivePrefsRepository(appContext) }

    override suspend fun upload(uri: Uri, folderId: String, fileName: String?): UploadResult {
        // 1) 認証トークン
        val token = auth.getAccessTokenOrNull()
            ?: return UploadResult.Failure(code = 401, body = "No Google access token")

        // 2) フォルダIDのプレフライト解決（ショートカット・権限・resourceKey 対応）
        val rk = try { prefs.folderResourceKeyFlow.first() } catch (_: Exception) { null }
        val resolved = client.resolveFolderIdForUpload(token, folderId, rk)
        val finalFolderId = when (resolved) {
            is ApiResult.Success -> resolved.data
            is ApiResult.HttpError -> {
                Log.w(LogTags.DRIVE, "Preflight NG code=${resolved.code} body=${resolved.body.take(160)}")
                return UploadResult.Failure(
                    code = resolved.code,
                    body = resolved.body,
                    message = "Preflight folder check failed"
                )
            }
            is ApiResult.NetworkError -> {
                Log.w(LogTags.DRIVE, "Preflight network: ${resolved.exception.message}")
                return UploadResult.Failure(
                    code = -1,
                    body = resolved.exception.message ?: "network",
                    message = "Preflight network"
                )
            }
        }

        // 3) URI からメタ情報と内容を取得（サンプル用途：全読み込み）
        val cr = appContext.contentResolver
        val pickedName = fileName ?: run {
            // DISPLAY_NAME が取れない場合はファイル名を推測
            val guess = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            guess ?: "upload.bin"
        }
        val pickedMime = cr.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(pickedName.substringAfterLast('.', ""))?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        val bytes = withContext(Dispatchers.IO) {
            cr.openInputStream(uri)?.use { it.readBytes() }
        } ?: return UploadResult.Failure(code = -1, body = "Failed to open input stream")

        // 4) Drive へ multipart/related アップロード
        val apiRes = client.uploadMultipart(
            token = token,
            name = pickedName,
            parentsId = finalFolderId,
            mimeType = pickedMime,
            bytes = bytes
        )

        // 5) ApiResult → UploadResult へ変換して返す
        return when (apiRes) {
            is ApiResult.Success -> {
                val r = apiRes.data
                UploadResult.Success(
                    id = r.id ?: "",
                    name = r.name ?: pickedName,
                    webViewLink = r.webViewLink ?: ""
                )
            }
            is ApiResult.HttpError -> {
                UploadResult.Failure(code = apiRes.code, body = apiRes.body)
            }
            is ApiResult.NetworkError -> {
                UploadResult.Failure(
                    code = -1,
                    body = apiRes.exception.message ?: "network",
                    message = "Network error"
                )
            }
        }
    }
}

/** エンジンに応じて Uploader を生成。NONE のときは null。*/
object UploaderFactory {
    fun create(context: Context, engine: UploadEngine): Uploader? =
        when (engine) {
            // 既定は Resumable 対応の KotlinDriveUploader（OkHttp直叩き版）を使いたい場合は、
            // ここを KotlinDriveUploader(context) にしてください。
            UploadEngine.KOTLIN -> ApiClientDriveUploader(context)
            UploadEngine.NONE   -> null
        }
}
