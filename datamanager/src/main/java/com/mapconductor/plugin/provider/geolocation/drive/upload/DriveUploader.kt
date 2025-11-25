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

/** Common interface for upload engines. Takes a URI and returns UploadResult. */
interface Uploader {
    suspend fun upload(
        uri: Uri,
        folderId: String,
        fileName: String? = null
    ): UploadResult
}

/**
 * Legacy implementation using DriveApiClient + GoogleAuthRepository internally.
 *
 * @deprecated Depends on GoogleAuthUtil-based implementation; for new code prefer KotlinDriveUploader
 *             together with GoogleDriveTokenProvider (for example Credential Manager).
 */
@Deprecated("Use KotlinDriveUploader via UploaderFactory with a modern GoogleDriveTokenProvider")
internal class ApiClientDriveUploader(
    private val appContext: Context,
    private val client: DriveApiClient = DriveApiClient(appContext),
    private val auth: GoogleAuthRepository = GoogleAuthRepository(appContext)
) : Uploader {

    // prefs used for reading resourceKey.
    private val prefs by lazy { DrivePrefsRepository(appContext) }

    override suspend fun upload(uri: Uri, folderId: String, fileName: String?): UploadResult {
        // 1) Acquire auth token.
        val token = auth.getAccessTokenOrNull()
            ?: return UploadResult.Failure(code = 401, body = "No Google access token")

        // 2) Resolve folder ID (handles shortcut, permissions, resourceKey).
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

        // 3) Read metadata and content from URI (simple full read).
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

        // 4) Perform multipart/related upload via DriveApiClient.
        val apiRes = client.uploadMultipart(
            token = token,
            name = pickedName,
            parentsId = finalFolderId,
            mimeType = pickedMime,
            bytes = bytes
        )

        // 5) Map ApiResult to UploadResult.
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

/** Factory that returns Uploader based on engine type. NONE returns null. */
object UploaderFactory {
    /**
     * Create Uploader based on engine type and token provider.
     *
     * @param context Application context.
     * @param engine  Upload engine to use.
     * @param tokenProvider Token provider to use. When null, DriveTokenProviderRegistry is consulted;
     *                      if still null, falls back to legacy GoogleAuthRepository.
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
     * Legacy factory method that uses ApiClientDriveUploader internally.
     *
     * @deprecated Prefer create(context, engine, tokenProvider) for better flexibility.
     */
    @Deprecated("Use create(context, engine, tokenProvider) for better flexibility")
    fun createLegacy(context: Context, engine: UploadEngine): Uploader? =
        when (engine) {
            UploadEngine.KOTLIN -> ApiClientDriveUploader(context)
            UploadEngine.NONE   -> null
        }
}

