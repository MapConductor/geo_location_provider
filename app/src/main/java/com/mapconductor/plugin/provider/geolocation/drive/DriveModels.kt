package com.mapconductor.plugin.provider.geolocation.drive

/** GET /about の最小レスポンスモデル */
data class AboutResponse(val user: User?) {
    data class User(
        val displayName: String?,
        val emailAddress: String?
    )
}

/** フォルダ検証用モデル（mimeTypeでフォルダ判定） */
data class FolderInfo(
    val id: String,
    val name: String,
    val mimeType: String
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}
