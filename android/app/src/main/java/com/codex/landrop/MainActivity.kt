package com.codex.landrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.codex.landrop.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: UploadRepository
    private val pickedUris = mutableListOf<Uri>()
    private var macFiles = emptyList<MacDownloadFile>()
    private var currentUploadId: UUID? = null
    private var macFilesPollingJob: Job? = null
    private var pendingReceiveIndex: Int? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Uploads still work if the user declines; only the foreground notification is hidden.
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val index = pendingReceiveIndex
        pendingReceiveIndex = null
        if (granted && index != null) {
            receiveMacFile(index)
        } else {
            toast("没有存储权限，无法保存到 Downloads")
        }
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

        binding.openMacFilesButton.setOnClickListener {
            saveDraft()
            refreshMacFilesWithDiscovery()
        }

        handleIncomingShare(intent)
    }

    override fun onStart() {
        super.onStart()
        startMacFilesPolling()
    }

    override fun onStop() {
        macFilesPollingJob?.cancel()
        macFilesPollingJob = null
        super.onStop()
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

    private fun discoverMac(autoUploadAfterDiscovery: Boolean = false, openFilesAfterDiscovery: Boolean = false) {
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
                if (openFilesAfterDiscovery) {
                    refreshMacFiles()
                } else if (autoUploadAfterDiscovery && pickedUris.isNotEmpty() && !device.authRequired) {
                    uploadSelectedFiles()
                } else {
                    setBusy(false)
                    refreshMacFiles(silent = true)
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

    private fun refreshMacFilesWithDiscovery() {
        val serverUrl = binding.serverUrlInput.text?.toString().orEmpty().trim()
        if (serverUrl.isEmpty()) {
            discoverMac(openFilesAfterDiscovery = true)
            return
        }
        refreshMacFiles()
    }

    private fun refreshMacFiles(silent: Boolean = false) {
        val serverUrl = binding.serverUrlInput.text?.toString().orEmpty().trim()
        if (serverUrl.isEmpty()) {
            if (!silent) {
                binding.macFilesStatus.text = "先点“自动发现 Mac”，找到电脑后这里会显示 Mac 发来的文件。"
            }
            return
        }
        if (!silent) {
            binding.macFilesStatus.text = "正在查看 Mac 发来的文件…"
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.listMacFiles(serverUrl)
            }
            if (result.isSuccess) {
                renderMacFiles(result.getOrThrow())
            } else if (!silent) {
                binding.macFilesStatus.text = result.exceptionOrNull()?.message ?: "查看失败，请确认 Mac 端还开着。"
            }
        }
    }

    private fun renderMacFiles(response: MacFilesResponse) {
        macFiles = response.files
        binding.macFilesList.removeAllViews()

        binding.macFilesStatus.text = when {
            response.activeTransferCount > 0 && response.activeTransferSenders.isNotEmpty() ->
                getString(R.string.mac_files_sending_named, response.activeTransferSenders.joinToString("、"))
            response.activeTransferCount > 0 -> getString(R.string.mac_files_sending)
            response.files.isNotEmpty() -> getString(R.string.mac_files_ready, response.files.size)
            else -> getString(R.string.mac_files_empty)
        }

        response.files.forEachIndexed { index, file ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = getDrawable(R.drawable.panel_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
            }

            item.addView(TextView(this).apply {
                text = file.name
                setTextAppearance(android.R.style.TextAppearance_Material_Medium)
            })
            item.addView(TextView(this).apply {
                text = listOf(file.sizeLabel, file.modifiedAt.take(16).replace("T", " ")).filter { it.isNotBlank() }.joinToString(" · ")
                setTextAppearance(android.R.style.TextAppearance_Material_Small)
                alpha = 0.72f
                setPadding(0, dp(4), 0, dp(6))
            })
            item.addView(MaterialButton(this).apply {
                text = getString(R.string.receive_file)
                setOnClickListener { receiveMacFile(index) }
            })
            binding.macFilesList.addView(item)
        }
    }

    private fun receiveMacFile(index: Int) {
        val file = macFiles.getOrNull(index) ?: return
        if (needsLegacyStoragePermission()) {
            pendingReceiveIndex = index
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val serverUrl = binding.serverUrlInput.text?.toString().orEmpty().trim()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.receiveMacFile(serverUrl, file)
            }
            if (result.isSuccess) {
                binding.statusText.text = getString(R.string.download_started, file.name)
                toast("已开始接收")
            } else {
                val message = result.exceptionOrNull()?.message ?: "接收失败"
                binding.statusText.text = message
                toast(message)
            }
        }
    }

    private fun startMacFilesPolling() {
        if (macFilesPollingJob?.isActive == true) return
        macFilesPollingJob = lifecycleScope.launch {
            while (isActive) {
                refreshMacFiles(silent = true)
                delay(3500)
            }
        }
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
        binding.openMacFilesButton.isEnabled = !busy
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

    private fun needsLegacyStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
