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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
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
    private val discoveryHttpClient = OkHttpClient.Builder()
        .connectTimeout(350, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .writeTimeout(600, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
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
        discoverViaUdp(timeoutMillis)?.let { return@runCatching it }
        scanLocalNetwork()?.let { return@runCatching it }
        error("没有发现 Mac。已尝试自动广播和局域网扫描，请确认手机和 Mac 在同一个 Wi-Fi，或手动输入 Mac 页面显示的地址。")
    }

    private fun discoverViaUdp(timeoutMillis: Int): DiscoveredDevice? {
        val requestJson = JSONObject()
            .put("type", "discover")
            .put("device_name", android.os.Build.MODEL ?: "Android")
            .toString()
            .toByteArray(Charsets.UTF_8)

        var discovered: DiscoveredDevice? = null
        DatagramSocket().use { socket ->
            socket.broadcast = true
            discoveryBroadcastAddresses().forEach { address ->
                val packet = DatagramPacket(requestJson, requestJson.size, address, 50000)
                runCatching { socket.send(packet) }
            }

            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
            while (System.nanoTime() < deadline) {
                try {
                    val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                    socket.soTimeout = remainingMillis.coerceAtMost(500).toInt()
                    socket.receive(response)
                    val text = String(response.data, 0, response.length, Charsets.UTF_8)
                    val json = JSONObject(text)
                    if (json.optString("type") != "response") continue
                    discovered = deviceFromStatusJson(json)
                    return@use
                } catch (_: SocketTimeoutException) {
                    // Keep listening until the overall discovery window expires.
                }
            }
        }
        return discovered
    }

    private fun scanLocalNetwork(timeoutMillis: Int = 4500): DiscoveredDevice? {
        val hosts = localSubnetHosts()
        if (hosts.isEmpty()) return null

        val executor = Executors.newFixedThreadPool(32)
        val completion = ExecutorCompletionService<DiscoveredDevice?>(executor)
        hosts.forEach { host ->
            completion.submit(Callable { probeHost(host) })
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        try {
            for (index in hosts.indices) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
                val device = runCatching { future.get() }.getOrNull()
                if (device != null) return device
            }
        } finally {
            executor.shutdownNow()
        }
        return null
    }

    private fun probeHost(host: String): DiscoveredDevice? {
        val url = "http://$host:4318/api/status"
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            discoveryHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                if (!json.optBoolean("ok", false)) return@use null
                deviceFromStatusJson(json, fallbackIp = host)
            }
        }.getOrNull()
    }

    private fun deviceFromStatusJson(json: JSONObject, fallbackIp: String = ""): DiscoveredDevice {
        val ip = json.optString("ip").ifBlank { fallbackIp }
        val port = json.optInt("port", 4318)
        val baseUrl = json.optString("baseUrl").ifBlank { "http://$ip:$port" }
        return DiscoveredDevice(
            name = json.optString("device_name")
                .ifBlank { json.optString("deviceName") }
                .ifBlank { "Mac" },
            ip = ip,
            port = port,
            baseUrl = baseUrl,
            authRequired = json.optBoolean("authRequired", true)
        )
    }

    private fun discoveryBroadcastAddresses(): List<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()
        addresses.add(InetAddress.getByName("255.255.255.255"))
        networkInterfaces().forEach { networkInterface ->
            networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                interfaceAddress.broadcast?.let { addresses.add(it) }
            }
        }
        return addresses.toList()
    }

    private fun localSubnetHosts(): List<String> {
        val hosts = linkedSetOf<String>()
        networkInterfaces().forEach { networkInterface ->
            networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                val address = interfaceAddress.address as? Inet4Address ?: return@forEach
                if (address.isLoopbackAddress || address.isLinkLocalAddress) return@forEach
                val hostAddress = address.hostAddress ?: return@forEach
                val parts = hostAddress.split(".")
                if (parts.size != 4) return@forEach
                val prefix = parts.take(3).joinToString(".")
                val ownHost = parts.last().toIntOrNull()
                for (host in 1..254) {
                    if (host == ownHost) continue
                    hosts.add("$prefix.$host")
                }
            }
        }
        return hosts.toList()
    }

    private fun networkInterfaces(): List<NetworkInterface> {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
        }.getOrDefault(emptyList())
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
