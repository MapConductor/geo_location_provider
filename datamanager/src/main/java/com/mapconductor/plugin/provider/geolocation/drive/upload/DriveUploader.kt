@file:Suppress("DEPRECATION")

package com.mapconductor.plugin.provider.geolocation.drive.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.mapconductor.plugin.provider.geolocation.drive.ApiResult
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult
import com.mapconductor.plugin.provider.geolocation.drive.auth.DriveTokenProviderRegistry
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.drive.auth.GoogleDriveTokenProvider
import com.mapconductor.plugin.provider.geolocation.prefs.DrivePrefsRepository
import com.mapconductor.plugin.provider.geolocation.util.LogTags
import com.mapconductor.plugin.provider.geolocation.config.UploadEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** アップロード方式の共通インターフェース。URI を受け取り、結果を UploadResult として返す。 */
interface Uploader {
    suspend fun upload(
        uri: Uri,
        folderId: String,
        fileName: String? = null
    ): UploadResult
}

/**
 * 既存の DriveApiClient + GoogleAuthRepository を内部で利用するレガシー実装。
 *
 * @deprecated GoogleAuthUtil ベースの実装に依存するため、新規コードでは KotlinDriveUploader と
 *             GoogleDriveTokenProvider（Credential Manager など）の組み合わせを使用してください。
 */
@Deprecated("Use KotlinDriveUploader via UploaderFactory with a modern GoogleDriveTokenProvider")
internal class ApiClientDriveUploader(
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

        // 2) フォルダ ID のプレフライト解決（ショートカット・権限・resourceKey を考慮）
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

        // 3) URI からメタ情報と内容を取得（ここではシンプルに全読み込み）
        val cr = appContext.contentResolver
        val pickedName = fileName ?: run {
            val guess = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
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

/** エンジン種別に応じて Uploader を生成するファクトリ。NONE のときは null を返す。 */
object UploaderFactory {
    /**
     * エンジン種別とトークンプロバイダに応じて Uploader を生成する。
     *
     * @param context Application コンテキスト
     * @param engine  利用するアップロードエンジン
     * @param tokenProvider 利用するトークンプロバイダ。未指定の場合は DriveTokenProviderRegistry を参照し、
     *                      未登録ならレガシーな GoogleAuthRepository にフォールバックする。
     */
    fun create(
        context: Context,
        engine: UploadEngine,
        tokenProvider: GoogleDriveTokenProvider? = null
    ): Uploader? =
        when (engine) {
            UploadEngine.KOTLIN -> {
                val provider = tokenProvider ?: DriveTokenProviderRegistry.getBackgroundProvider()
                if (provider != null) {
                    KotlinDriveUploader(context, provider)
                } else {
                    @Suppress("DEPRECATION")
                    KotlinDriveUploader(context, GoogleAuthRepository(context))
                }
            }
            UploadEngine.NONE   -> null
        }

    /**
     * レガシーなファクトリメソッド。内部的に ApiClientDriveUploader を利用する。
     *
     * @deprecated create(context, engine, tokenProvider) の利用を推奨
     */
    @Deprecated("Use create(context, engine, tokenProvider) for better flexibility")
    fun createLegacy(context: Context, engine: UploadEngine): Uploader? =
        when (engine) {
            UploadEngine.KOTLIN -> ApiClientDriveUploader(context)
            UploadEngine.NONE   -> null
        }
}
