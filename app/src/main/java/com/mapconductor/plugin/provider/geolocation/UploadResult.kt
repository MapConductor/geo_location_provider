package com.mapconductor.plugin.provider.geolocation.drive

/** Drive へのアップロード結果 */
sealed class UploadResult {
    /** HTTP 2xx 成功 */
    data class Success(
        val id: String,
        val name: String,
        val webViewLink: String
    ) : UploadResult()

    /** 失敗（HTTP非2xxやネットワーク例外時の便宜的なコード -1 など） */
    data class Failure(
        val code: Int,
        val body: String
    ) : UploadResult()
}
