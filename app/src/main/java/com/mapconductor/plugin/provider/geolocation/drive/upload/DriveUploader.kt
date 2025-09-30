package com.mapconductor.plugin.provider.geolocation.drive.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.util.LogTags
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import kotlinx.coroutines.flow.first

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

    // ★ 追加：resourceKey を読むための prefs（コンストラクタは据え置き）
    private val prefs by lazy { DrivePrefsRepository(appContext) }

    override suspend fun upload(uri: Uri, folderId: String, fileName: String?): UploadResult {
        val token = auth.getAccessTokenOrNull()
            ?: return UploadResult.Failure(code = 401, body = "No Google access token")

        // ★ 追加：保存済み resourceKey を使って「実体フォルダID & 書込可」をプレフライト解決
        val rk = try { prefs.folderResourceKeyFlow.first() } catch (_: Exception) { null }
        val resolve = client.resolveFolderIdForUpload(token, folderId, rk)
        val finalFolderId = when (resolve) {
            is ApiResult.Success -> resolve.data
            is ApiResult.HttpError -> {
                Log.w(LogTags.DRIVE, "Preflight NG code=${resolve.code} body=${resolve.body.take(160)}")
                return UploadResult.Failure(code = resolve.code, body = resolve.body, message = "Preflight folder check failed")
            }
            is ApiResult.NetworkError -> {
                Log.w(LogTags.DRIVE, "Preflight network: ${resolve.exception.message}")
                return UploadResult.Failure(code = -1, body = resolve.exception.message ?: "network", message = "Preflight network")
            }
        }

        // 以降は従来どおり multipart（※ DriveApiClient 側で supportsAllDrives=true 済）
        val result = client.uploadMultipart(
            token = token,
            uri = uri,
            fileName = fileName,
            folderId = finalFolderId
        )
        when (result) {
            is UploadResult.Success -> Log.i(LogTags.DRIVE, "Upload OK id=${result.id}")
            is UploadResult.Failure -> Log.w(LogTags.DRIVE, "Upload NG code=${result.code} body=${result.body.take(160)}")
        }
        return result
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
