package com.mapconductor.plugin.provider.geolocation.drive

import java.io.IOException

/** Drive API 呼び出しの結果型（成功 / HTTPエラー / ネットワーク例外） */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class HttpError(val code: Int, val body: String) : ApiResult<Nothing>()
    data class NetworkError(val exception: IOException) : ApiResult<Nothing>()
}
