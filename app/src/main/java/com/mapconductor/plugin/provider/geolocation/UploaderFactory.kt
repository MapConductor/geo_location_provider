package com.mapconductor.plugin.provider.geolocation.drive.upload

import android.content.Context
import com.mapconductor.plugin.provider.geolocation.UploadEngine

object UploaderFactory {
    /**
     * エンジンに応じた Uploader を返す。NONE のときは null。
     */
    fun create(context: Context, engine: UploadEngine): Uploader? =
        when (engine) {
            UploadEngine.KOTLIN -> KotlinDriveUploader(context)
            UploadEngine.NONE   -> null
        }
}
