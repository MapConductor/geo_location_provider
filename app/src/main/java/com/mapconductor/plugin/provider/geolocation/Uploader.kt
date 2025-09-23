package com.mapconductor.plugin.provider.geolocation.drive.upload

import android.net.Uri
import com.mapconductor.plugin.provider.geolocation.drive.UploadResult

/**
 * アップロード方式の共通境界。URI を受け取り、結果を UploadResult で返す。
 */
interface Uploader {
    suspend fun upload(
        uri: Uri,
        folderId: String,
        fileName: String? = null
    ): UploadResult
}
