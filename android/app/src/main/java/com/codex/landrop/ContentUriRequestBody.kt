package com.codex.landrop

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

class ContentUriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val mediaType: MediaType?,
    private val onUploaded: () -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long {
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    override fun writeTo(sink: BufferedSink) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            sink.writeAll(input.source())
        } ?: error("无法读取文件：$uri")
        onUploaded()
    }
}
