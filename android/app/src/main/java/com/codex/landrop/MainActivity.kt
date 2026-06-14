package com.codex.landrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.codex.landrop.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: UploadRepository
    private val pickedUris = mutableListOf<Uri>()
    private var currentUploadId: UUID? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Uploads still work if the user declines; only the foreground notification is hidden.
    }

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        pickedUris.clear()
        uris.forEach { uri ->
            persistReadPermission(uri)
            pickedUris.add(uri)
        }
        updateSelectedFiles()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = UploadRepository(applicationContext)
        restoreDraft()
        updateSelectedFiles()
        requestNotificationPermissionIfNeeded()

        binding.pickFilesButton.setOnClickListener {
            pickFilesLauncher.launch(arrayOf("*/*"))
        }

        binding.testConnectionButton.setOnClickListener {
            saveDraft()
            testConnection()
        }

        binding.discoverButton.setOnClickListener {
            discoverMac()
        }

        binding.uploadButton.setOnClickListener {
            saveDraft()
            uploadSelectedFiles()
        }

        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun restoreDraft() {
        binding.serverUrlInput.setText(repository.getServerUrl())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun saveDraft() {
        repository.saveServerUrl(binding.serverUrlInput.text?.toString().orEmpty())
    }

    private fun updateSelectedFiles() {
        binding.selectedCount.text = getString(R.string.selected_count, pickedUris.size)
        binding.selectedFilesText.text = if (pickedUris.isEmpty()) {
            getString(R.string.no_files_selected)
        } else {
            pickedUris.joinToString("\n") { uri ->
                DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
            }
        }
    }

    private fun testConnection() {
        val serverUrl = binding.serverUrlInput.text?.toString().orEmpty().trim()
        if (serverUrl.isEmpty()) {
            toast("先填接收地址")
            return
        }

        setBusy(true, getString(R.string.testing_connection))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.testConnection(serverUrl)
            }
            setBusy(false)
            if (result.isSuccess) {
                binding.statusText.text = getString(R.string.connection_ok, result.getOrThrow())
                toast("连接成功")
            } else {
                val message = result.exceptionOrNull()?.message ?: "连接失败"
                binding.statusText.text = message
                toast(message)
            }
        }
    }

    private fun discoverMac(autoUploadAfterDiscovery: Boolean = false) {
        setBusy(true, getString(R.string.discovering_mac))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.discoverMac()
            }
            if (result.isSuccess) {
                val device = result.getOrThrow()
                binding.serverUrlInput.setText(device.baseUrl)
                repository.saveServerUrl(device.baseUrl)
                binding.statusText.text = if (device.authRequired) {
                    getString(R.string.discovered_mac_requires_code, device.name, device.baseUrl)
                } else {
                    getString(R.string.discovered_mac, device.name, device.baseUrl)
                }
                toast("已发现 Mac")
                if (autoUploadAfterDiscovery && pickedUris.isNotEmpty() && !device.authRequired) {
                    uploadSelectedFiles()
                } else {
                    setBusy(false)
                }
            } else {
                val message = result.exceptionOrNull()?.message ?: "发现失败"
                binding.statusText.text = message
                toast(message)
                setBusy(false)
            }
        }
    }

    private fun uploadSelectedFiles() {
        val serverUrl = binding.serverUrlInput.text?.toString().orEmpty().trim()
        if (serverUrl.isEmpty()) {
            discoverMac(autoUploadAfterDiscovery = true)
            return
        }
        if (pickedUris.isEmpty()) {
            toast("先选文件")
            return
        }

        val uploadId = repository.enqueueBackgroundUpload(serverUrl, pickedUris.toList())
        currentUploadId = uploadId
        setBusy(true, getString(R.string.uploading_background))
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = 100
        binding.progressBar.progress = 0
        observeUpload(uploadId)
        toast("已开始后台上传")
    }

    private fun observeUpload(uploadId: UUID) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(uploadId).observe(this) { info ->
            if (info == null || currentUploadId != uploadId) return@observe

            val percent = info.progress.getInt(UploadWorker.KEY_PERCENT, 0)
            val message = info.progress.getString(UploadWorker.KEY_MESSAGE).orEmpty()
            val timeLabel = info.progress.getString(UploadWorker.KEY_TIME_LABEL).orEmpty()
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressText.visibility = View.VISIBLE
                    binding.progressBar.isIndeterminate = info.state == WorkInfo.State.ENQUEUED
                    binding.progressBar.progress = percent
                    binding.progressText.text = if (timeLabel.isBlank()) {
                        getString(R.string.upload_progress_percent, percent)
                    } else {
                        getString(R.string.upload_progress_with_time, percent, timeLabel)
                    }
                    if (message.isNotBlank()) {
                        binding.statusText.text = message
                    }
                }
                WorkInfo.State.SUCCEEDED -> {
                    val count = info.outputData.getInt(UploadWorker.KEY_COUNT, pickedUris.size)
                    val savedTo = info.outputData.getString(UploadWorker.KEY_SAVED_TO).orEmpty()
                    currentUploadId = null
                    setBusy(false)
                    pickedUris.clear()
                    updateSelectedFiles()
                    binding.statusText.text = getString(R.string.upload_success, count, savedTo)
                    toast("上传完成")
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    currentUploadId = null
                    setBusy(false)
                    val error = info.outputData.getString(UploadWorker.KEY_ERROR) ?: "上传失败"
                    binding.statusText.text = error
                    toast(error)
                }
            }
        }
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val sharedUris = when (intent.action) {
            Intent.ACTION_SEND -> singleSharedUri(intent)?.let { listOf(it) }.orEmpty()
            Intent.ACTION_SEND_MULTIPLE -> multipleSharedUris(intent)
            else -> emptyList()
        }
        if (sharedUris.isEmpty()) return

        pickedUris.clear()
        sharedUris.forEach { uri ->
            persistReadPermission(uri)
            pickedUris.add(uri)
        }
        updateSelectedFiles()
        binding.statusText.text = getString(R.string.shared_files_ready, pickedUris.size)
        uploadSelectedFiles()
    }

    private fun singleSharedUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun multipleSharedUris(intent: Intent): List<Uri> {
        val fromExtras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }.orEmpty()
        if (fromExtras.isNotEmpty()) return fromExtras

        val clipData = intent.clipData ?: return emptyList()
        return List(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Shared media grants are often temporary; WorkManager starts immediately and can use them.
        }
    }

    private fun setBusy(busy: Boolean, status: String? = null) {
        binding.pickFilesButton.isEnabled = !busy
        binding.discoverButton.isEnabled = !busy
        binding.testConnectionButton.isEnabled = !busy
        binding.uploadButton.isEnabled = !busy
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressText.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressBar.isIndeterminate = busy
        if (status != null) {
            binding.statusText.text = status
        }
        if (!busy) {
            binding.progressText.text = ""
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
