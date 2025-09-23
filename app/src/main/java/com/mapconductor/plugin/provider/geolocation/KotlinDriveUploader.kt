package com.mapconductor.plugin.provider.geolocation.drive.upload

import android.content.Context
import android.util.Log
import android.net.Uri
import com.mapconductor.plugin.provider.geolocation.GoogleAuthRepository
import com.mapconductor.plugin.provider.geolocation.LogTags
import com.mapconductor.plugin.provider.geolocation.drive.DriveApiClient
import com.mapconductor.plugin.provider.geolocation.drive.DriveFolderId
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult

/**
 * 既存の DriveApiClient + GoogleAuthRepository を内部で利用する実装。
 */
class KotlinDriveUploader(
    private val appContext: Context,
    private val client: DriveApiClient = DriveApiClient(appContext),
    private val auth: GoogleAuthRepository = GoogleAuthRepository(appContext)
) : Uploader {

    override suspend fun upload(uri: Uri, folderId: String, fileName: String?): UploadResult {
        val token = auth.getAccessTokenOrNull()
            ?: return UploadResult.Failure(code = 401, body = "No Google access token")

        val resolvedFolderId = DriveFolderId.extractFromUrlOrId(folderId)
            ?: return UploadResult.Failure(code = 400, body = "Invalid folderId")

        val result = client.uploadMultipart(
            token = token,
            uri = uri,
            fileName = fileName,
            folderId = resolvedFolderId
        )

        when (result) {
            is UploadResult.Success -> Log.i(LogTags.DRIVE, "Upload OK id=${result.id}")
            is UploadResult.Failure -> Log.w(LogTags.DRIVE, "Upload NG code=${result.code}")
        }
        return result
    }
}
