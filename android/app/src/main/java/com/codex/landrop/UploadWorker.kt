package com.codex.landrop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UploadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val serverUrl = inputData.getString(KEY_SERVER_URL).orEmpty()
        val jobId = inputData.getString(KEY_JOB_ID).orEmpty()
        if (serverUrl.isBlank() || jobId.isBlank()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "上传任务参数不完整"))
        }

        val uris = readJobUris(jobId)
        if (uris.isEmpty()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "没有可上传的文件"))
        }

        try {
            setForeground(createForegroundInfo(0, "准备上传"))
            val fileInfos = uris.map { uri ->
                UploadFileInfo(
                    uri = uri,
                    displayName = resolveDisplayName(uri),
                    size = resolveSize(uri)
                )
            }
            val totalBytes = fileInfos.sumOf { it.size }.coerceAtLeast(1L)
            val uploadStartedAt = System.currentTimeMillis()
            var completedBytes = 0L
            var savedTo = ""

            try {
                fileInfos.forEachIndexed { index, file ->
                    if (file.size <= 0) {
                        throw IllegalStateException("无法读取文件大小：${file.displayName}")
                    }
                    savedTo = uploadOneFile(
                        serverUrl = serverUrl,
                        file = file,
                        fileIndex = index + 1,
                        fileTotal = fileInfos.size,
                        completedBeforeFile = completedBytes,
                        totalBytes = totalBytes,
                        startedAtMillis = uploadStartedAt
                    ).ifBlank { savedTo }
                    completedBytes += file.size
                }
            } catch (_: ResumableUploadNotFoundException) {
                savedTo = legacyUploadAll(serverUrl, fileInfos, totalBytes, uploadStartedAt)
            }

            publishProgress(100, "上传完成", totalBytes, totalBytes, uploadStartedAt)
            clearJob(jobId)
            Result.success(
                workDataOf(
                    KEY_COUNT to fileInfos.size,
                    KEY_SAVED_TO to savedTo
                )
            )
        } catch (error: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "上传失败")))
        }
    }

    private suspend fun uploadOneFile(
        serverUrl: String,
        file: UploadFileInfo,
        fileIndex: Int,
        fileTotal: Int,
        completedBeforeFile: Long,
        totalBytes: Long,
        startedAtMillis: Long
    ): String {
        val sessionId = makeSessionId(file.displayName, file.size, file.uri.toString())
        val start = openSession(serverUrl, file, sessionId)
        if (start.complete) {
            publishProgress(
                percent(completedBeforeFile + file.size, totalBytes),
                "已跳过 ${file.displayName}",
                completedBeforeFile + file.size,
                totalBytes,
                startedAtMillis
            )
            return ""
        }

        var offset = start.offset
        publishProgress(
            percent(completedBeforeFile + offset, totalBytes),
            "上传 $fileIndex/$fileTotal：${file.displayName}",
            completedBeforeFile + offset,
            totalBytes,
            startedAtMillis
        )

        appContext.contentResolver.openInputStream(file.uri)?.use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(CHUNK_SIZE)
            while (offset < file.size) {
                val read = input.read(buffer)
                if (read <= 0) break
                val nextOffset = uploadChunk(serverUrl, sessionId, offset, buffer.copyOf(read))
                offset = nextOffset
                publishProgress(
                    percent(completedBeforeFile + offset, totalBytes),
                    "上传 $fileIndex/$fileTotal：${file.displayName}",
                    completedBeforeFile + offset,
                    totalBytes,
                    startedAtMillis
                )
            }
        } ?: throw IllegalStateException("无法读取文件：${file.displayName}")

        val complete = completeSession(serverUrl, sessionId)
        return complete.optString("savedTo")
    }

    private fun openSession(serverUrl: String, file: UploadFileInfo, sessionId: String): UploadSessionStart {
        val json = JSONObject()
            .put("sessionId", sessionId)
            .put("filename", file.displayName)
            .put("totalSize", file.size)
            .put("lastModified", file.uri.toString())
        val request = Request.Builder()
            .url(buildUrl(serverUrl, "/api/upload-session"))
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (response.code == 404) {
                throw ResumableUploadNotFoundException()
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(parseServerError(response.code, payload))
            }
            val data = JSONObject(payload)
            return UploadSessionStart(
                offset = data.optLong("offset", 0L),
                complete = data.optBoolean("complete", false)
            )
        }
    }

    private suspend fun legacyUploadAll(
        serverUrl: String,
        files: List<UploadFileInfo>,
        totalBytes: Long,
        startedAtMillis: Long
    ): String {
        publishProgress(0, "Mac 端使用兼容上传", 0L, totalBytes, startedAtMillis)
        var completedBytes = 0L
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                files.forEach { file ->
                    val mimeType = appContext.contentResolver.getType(file.uri)?.toMediaTypeOrNull()
                    addFormDataPart(
                        "files",
                        file.displayName,
                        ContentUriRequestBody(appContext, file.uri, mimeType) {
                            completedBytes += file.size.coerceAtLeast(0L)
                            runBlocking {
                                publishProgress(
                                    percent(completedBytes, totalBytes),
                                    "兼容上传：${file.displayName}",
                                    completedBytes,
                                    totalBytes,
                                    startedAtMillis
                                )
                            }
                        }
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url(buildUrl(serverUrl, "/api/upload"))
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(parseServerError(response.code, payload))
            }
            publishProgress(100, "上传完成", totalBytes, totalBytes, startedAtMillis)
            return JSONObject(payload).optString("savedTo")
        }
    }

    private fun uploadChunk(serverUrl: String, sessionId: String, offset: Long, bytes: ByteArray): Long {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("sessionId", sessionId)
            .addFormDataPart("offset", offset.toString())
            .addFormDataPart("chunk", "chunk.bin", bytes.toRequestBody(CHUNK_MEDIA_TYPE))
            .build()
        val request = Request.Builder()
            .url(buildUrl(serverUrl, "/api/upload-chunk"))
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(payload) }.getOrNull()
            if (response.code == 409) {
                val nextOffset = data?.optLong("nextOffset", offset) ?: offset
                if (nextOffset != offset) return nextOffset
            }
            if (!response.isSuccessful) {
                throw IllegalStateException(parseServerError(response.code, payload))
            }
            return data?.optLong("nextOffset", offset + bytes.size) ?: offset + bytes.size
        }
    }

    private fun completeSession(serverUrl: String, sessionId: String): JSONObject {
        val request = Request.Builder()
            .url(buildUrl(serverUrl, "/api/upload-complete"))
            .post(JSONObject().put("sessionId", sessionId).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(parseServerError(response.code, payload))
            }
            return JSONObject(payload)
        }
    }

    private suspend fun publishProgress(
        percent: Int,
        message: String,
        doneBytes: Long? = null,
        totalBytes: Long? = null,
        startedAtMillis: Long? = null
    ) {
        val clamped = percent.coerceIn(0, 100)
        val timeLabel = transferTimeLabel(doneBytes, totalBytes, startedAtMillis)
        setProgress(
            workDataOf(
                KEY_PERCENT to clamped,
                KEY_MESSAGE to message,
                KEY_TIME_LABEL to timeLabel
            )
        )
        setForeground(createForegroundInfo(clamped, listOf(message, timeLabel).filter { it.isNotBlank() }.joinToString(" · ")))
    }

    private fun createForegroundInfo(percent: Int, message: String): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle("LAN Drop 正在上传")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(percent in 0..99)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LAN Drop 上传", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun readJobUris(jobId: String): List<Uri> {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(jobKey(jobId), "[]").orEmpty()
        val array = JSONArray(raw)
        return List(array.length()) { index -> array.getString(index).toUri() }
    }

    private fun clearJob(jobId: String) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(jobKey(jobId))
            .apply()
    }

    private fun resolveDisplayName(uri: Uri): String {
        queryOpenable(uri)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex) ?: "upload.bin"
            }
        }
        return uri.lastPathSegment ?: "upload.bin"
    }

    private fun resolveSize(uri: Uri): Long {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length > 0) return descriptor.length
        }
        queryOpenable(uri)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getLong(sizeIndex)
            }
        }
        return -1L
    }

    private fun queryOpenable(uri: Uri): Cursor? {
        return appContext.contentResolver.query(uri, null, null, null, null)
    }

    private fun skipFully(input: java.io.InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(scratch, 0, minOf(scratch.size, remaining.toInt()))
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    private fun percent(done: Long, total: Long): Int {
        return ((done.coerceAtLeast(0L) * 100) / total.coerceAtLeast(1L)).toInt()
    }

    private fun transferTimeLabel(doneBytes: Long?, totalBytes: Long?, startedAtMillis: Long?): String {
        val done = doneBytes ?: return ""
        val total = totalBytes ?: return ""
        val started = startedAtMillis ?: return ""
        val elapsedSeconds = ((System.currentTimeMillis() - started).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.1)
        val speedBytesPerSecond = done.coerceAtLeast(0L) / elapsedSeconds
        val remainingBytes = (total - done).coerceAtLeast(0L)
        val remainingSeconds = if (speedBytesPerSecond > 1.0) remainingBytes / speedBytesPerSecond else 0.0
        return "已用 ${formatDuration(elapsedSeconds)} · 剩余 ${formatDuration(remainingSeconds)} · ${formatBytes(speedBytesPerSecond)}/s"
    }

    private fun formatDuration(seconds: Double): String {
        val rounded = seconds.toLong().coerceAtLeast(0L)
        val hours = rounded / 3600
        val minutes = (rounded % 3600) / 60
        val secs = rounded % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    private fun formatBytes(bytes: Double): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index += 1
        }
        return if (index == 0) {
            "${value.toInt()} ${units[index]}"
        } else {
            "%.1f %s".format(value, units[index])
        }
    }

    private fun parseServerError(code: Int, payload: String): String {
        val error = runCatching { JSONObject(payload).optString("error") }.getOrDefault("")
        return when {
            code == 401 -> "Mac 端还在要求访问码，请重新打开桌面新版 LAN Drop.app"
            error.isNotBlank() -> "上传失败：$error"
            else -> "上传失败：HTTP $code"
        }
    }

    private fun buildUrl(serverUrl: String, path: String): String {
        val normalized = serverUrl.trim().removeSuffix("/")
        val prefixed = if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            "http://$normalized"
        }
        return prefixed.toUri().buildUpon().encodedPath(path).build().toString()
    }

    private fun makeSessionId(fileName: String, size: Long, identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$fileName:$size:$identity".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private data class UploadFileInfo(
        val uri: Uri,
        val displayName: String,
        val size: Long
    )

    private data class UploadSessionStart(
        val offset: Long,
        val complete: Boolean
    )

    private class ResumableUploadNotFoundException : Exception()

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_SERVER_URL = "serverUrl"
        const val KEY_PERCENT = "percent"
        const val KEY_MESSAGE = "message"
        const val KEY_TIME_LABEL = "timeLabel"
        const val KEY_COUNT = "count"
        const val KEY_SAVED_TO = "savedTo"
        const val KEY_ERROR = "error"
        const val PREFS_NAME = "lan_drop"

        private const val CHANNEL_ID = "lan_drop_uploads"
        private const val NOTIFICATION_ID = 4318
        private const val CHUNK_SIZE = 8 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val CHUNK_MEDIA_TYPE = "application/octet-stream".toMediaType()

        fun jobKey(jobId: String): String = "upload_job_$jobId"
    }
}
