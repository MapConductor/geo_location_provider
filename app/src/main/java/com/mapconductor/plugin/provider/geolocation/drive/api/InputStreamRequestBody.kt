package com.mapconductor.plugin.provider.geolocation.drive

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** ストリーミングでファイルを送る RequestBody */
class InputStreamRequestBody(
    private val mediaType: MediaType,
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentLength: Long = -1L
) : RequestBody() {
    override fun contentType(): MediaType = mediaType
    override fun contentLength(): Long = if (contentLength >= 0) contentLength else super.contentLength()
    override fun writeTo(sink: BufferedSink) {
        contentResolver.openInputStream(uri)?.use { input ->
            input.source().use { source -> sink.writeAll(source) }
        } ?: throw IllegalStateException("Unable to open input stream for $uri")
    }
}
