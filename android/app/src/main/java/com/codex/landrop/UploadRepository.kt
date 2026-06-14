package com.codex.landrop

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UploadResponse(
    val count: Int,
    val savedTo: String
)

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val baseUrl: String,
    val authRequired: Boolean
)

class UploadRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("lan_drop", Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getServerUrl(): String = prefs.getString("server_url", "") ?: ""

    fun saveServerUrl(value: String) {
        prefs.edit().putString("server_url", value.trim()).apply()
    }

    fun testConnection(serverUrl: String): Result<String> = runCatching {
        val url = buildUrl(serverUrl, "/api/status")
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("连接失败：HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            json.optString("baseUrl").ifBlank { url }
        }
    }

    fun discoverMac(timeoutMillis: Int = 3500): Result<DiscoveredDevice> = runCatching {
        val requestJson = JSONObject()
            .put("type", "discover")
            .put("device_name", android.os.Build.MODEL ?: "Android")
            .toString()
            .toByteArray(Charsets.UTF_8)

        var discovered: DiscoveredDevice? = null
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMillis
            val packet = DatagramPacket(
                requestJson,
                requestJson.size,
                InetAddress.getByName("255.255.255.255"),
                50000
            )
            socket.send(packet)

            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)
            while (true) {
                try {
                    socket.receive(response)
                    val text = String(response.data, 0, response.length, Charsets.UTF_8)
                    val json = JSONObject(text)
                    if (json.optString("type") != "response") continue
                    val baseUrl = json.optString("baseUrl").ifBlank {
                        "http://${json.optString("ip")}:${json.optInt("port", 4318)}"
                    }
                    discovered = DiscoveredDevice(
                        name = json.optString("device_name").ifBlank { "Mac" },
                        ip = json.optString("ip"),
                        port = json.optInt("port", 4318),
                        baseUrl = baseUrl,
                        authRequired = json.optBoolean("authRequired", true)
                    )
                    return@use
                } catch (_: SocketTimeoutException) {
                    error("没有发现 Mac。请确认 Mac 端 LAN Drop 已打开，手机和 Mac 在同一个 Wi-Fi。")
                }
            }
        }
        discovered ?: error("没有发现 Mac。请确认 Mac 端 LAN Drop 已打开，手机和 Mac 在同一个 Wi-Fi。")
    }

    fun uploadFiles(
        serverUrl: String,
        uris: List<Uri>,
        onProgress: (current: Int, total: Int) -> Unit
    ): Result<UploadResponse> = runCatching {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                uris.forEachIndexed { index, uri ->
                    val fileName = resolveDisplayName(uri)
                    val mimeType = context.contentResolver.getType(uri)?.toMediaTypeOrNull()
                    addFormDataPart(
                        "files",
                        fileName,
                        ContentUriRequestBody(context, uri, mimeType) {
                            onProgress(index + 1, uris.size)
                        }
                    )
                }
            }
            .build()

        val requestBuilder = Request.Builder()
            .url(buildUrl(serverUrl, "/api/upload"))
            .post(body)

        client.newCall(requestBuilder.build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(parseError(response.code, payload))
            }
            val json = JSONObject(payload)
            UploadResponse(
                count = json.optInt("count"),
                savedTo = json.optString("savedTo")
            )
        }
    }

    fun enqueueBackgroundUpload(serverUrl: String, uris: List<Uri>): UUID {
        val jobId = UUID.randomUUID().toString()
        val uriArray = JSONArray()
        uris.forEach { uri -> uriArray.put(uri.toString()) }
        prefs.edit()
            .putString(UploadWorker.jobKey(jobId), uriArray.toString())
            .apply()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                workDataOf(
                    UploadWorker.KEY_JOB_ID to jobId,
                    UploadWorker.KEY_SERVER_URL to serverUrl
                )
            )
            .addTag("lan_drop_upload")
            .build()
        workManager.enqueue(request)
        return request.id
    }

    private fun resolveDisplayName(uri: Uri): String {
        val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
        return documentFile?.name ?: uri.lastPathSegment ?: "upload.bin"
    }

    private fun parseError(code: Int, payload: String): String {
        if (code == 401) {
            return "Mac 端还在要求访问码。请退出 Mac 上的 LAN Drop，再打开桌面的新版 LAN Drop.app。"
        }
        val maybeJson = runCatching { JSONObject(payload) }.getOrNull()
        val error = maybeJson?.optString("error").orEmpty()
        return when {
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
}
