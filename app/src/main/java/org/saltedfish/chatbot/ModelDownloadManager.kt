package org.saltedfish.chatbot

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class ModelItem(
    val id: String,
    val displayName: String,
    val capability: ModelCapability,
    val remoteUrl: String,
    val localPath: String,
    val fileNames: List<String>,
    val extraArchives: List<ExtraArchive> = emptyList()
)

data class ExtraArchive(
    val displayName: String,
    val remoteUrl: String,
    val markerRelativePath: String,
    val assetPath: String? = null
)

enum class ModelCapability {
    TEXT,
    MULTIMODAL
}

object ModelConfig {
    private const val DEEPSEEK_OCR_BASE_URL =
        "https://www.modelscope.cn/api/v1/models/mllmTeam/DeepSeek-OCR-w4a8-i8mm-kai/repo?Revision=master&FilePath="
    private const val QWEN3_BASE_URL =
        "https://www.modelscope.cn/api/v1/models/mllmTeam/Qwen3-4B-w4a8-i8mm-kai/repo?Revision=master&FilePath="

    val models: List<ModelItem> = listOf(
        ModelItem(
            id = "qwen3",
            displayName = "Qwen3",
            capability = ModelCapability.TEXT,
            remoteUrl = QWEN3_BASE_URL,
            localPath = "/sdcard/Download/model/qwen3",
            fileNames = listOf(
                "model.mllm",
                "tokenizer.json",
                "config.json",
                "quant_cfg_4B_w4a32_kai.json"
            ),
            extraArchives = listOf(
                ExtraArchive(
                    displayName = "probes_linear.zip",
                    remoteUrl = QWEN3_BASE_URL + "probes_linear.zip",
                    markerRelativePath = "probes_linear",
                    assetPath = "model/probes_linear.zip"
                )
            )
        ),
        ModelItem(
            id = "deepseek_ocr",
            displayName = "DeepSeek OCR",
            capability = ModelCapability.MULTIMODAL,
            remoteUrl = DEEPSEEK_OCR_BASE_URL,
            localPath = "/sdcard/Download/model/deepseek_ocr",
            fileNames = listOf(
                "model.mllm",
                "tokenizer.json",
                "config.json"
            )
        )
    )

    fun findModelById(id: String): ModelItem? = models.firstOrNull { it.id == id }

    fun isModelDownloaded(model: ModelItem): Boolean {
        val dir = File(model.localPath)
        if (!dir.exists() || !dir.isDirectory) return false
        if (model.fileNames.isEmpty()) return false
        val coreReady = model.fileNames.all { File(dir, it).exists() }
        if (!coreReady) return false

        for (archive in model.extraArchives) {
            val marker = File(dir, archive.markerRelativePath)
            if (!marker.exists()) return false
            if (marker.isDirectory) {
                val hasAnyFile = marker.walkTopDown().any { it.isFile }
                if (!hasAnyFile) return false
            }
        }

        return true
    }

    fun isModelDownloaded(id: String): Boolean {
        val model = findModelById(id) ?: return false
        return isModelDownloaded(model)
    }
}

data class DownloadProgress(
    val modelId: String,
    val fileName: String,
    val percent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long
)

data class DownloadUiState(
    val visible: Boolean = false,
    val title: String = "",
    val percent: Int = 0,
    val statusText: String = ""
)

class ModelDownloadManager(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    suspend fun downloadModelById(
        context: Context,
        modelId: String,
        onProgress: (DownloadProgress) -> Unit,
        onStatus: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val model = ModelConfig.findModelById(modelId)
                ?: throw IllegalArgumentException("Unknown model id: $modelId")
            downloadModel(context, model, onProgress, onStatus)
        }
    }

    suspend fun ensureAllModels(
        context: Context,
        onProgress: (DownloadProgress) -> Unit,
        onStatus: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            for (model in ModelConfig.models) {
                if (model.remoteUrl.isBlank()) {
                    onStatus("${model.id} 下载链接未配置，已跳过")
                    continue
                }

                if (!needsDownload(model)) {
                    onStatus("${model.id} 已存在，跳过")
                    continue
                }

                downloadModel(context, model, onProgress, onStatus)
            }
        }
    }

    private fun needsDownload(model: ModelItem): Boolean = !ModelConfig.isModelDownloaded(model)

    private fun downloadModel(
        context: Context,
        model: ModelItem,
        onProgress: (DownloadProgress) -> Unit,
        onStatus: (String) -> Unit
    ) {
        val urlLower = model.remoteUrl.lowercase()
        val isZip = urlLower.endsWith(".zip")

        if (model.fileNames.size > 1 && !isZip) {
            val baseUrl = model.remoteUrl
            for (fileName in model.fileNames) {
                val fileUrl = "$baseUrl$fileName"
                val tempFile = File(context.cacheDir, "${model.id}-$fileName.download")
                onStatus("正在下载 ${model.id}: $fileName ...")
                downloadFile(model.id, fileName, fileUrl, tempFile, onProgress)
                onStatus("正在保存 ${model.id}: $fileName ...")
                saveToTarget(model, tempFile, fileName)
            }
            downloadExtraArchives(context, model, onProgress, onStatus)
            return
        }

        val tempFile = File(context.cacheDir, "${model.id}.download")
        onStatus("正在下载 ${model.id} ...")
        val displayFileName = model.fileNames.firstOrNull() ?: tempFile.name
        downloadFile(model.id, displayFileName, model.remoteUrl, tempFile, onProgress)

        if (isZip) {
            onStatus("正在解压 ${model.id} ...")
            unzipToDirectory(tempFile, File(model.localPath))
            tempFile.delete()
        } else {
            onStatus("正在保存 ${model.id} ...")
            saveToTarget(model, tempFile, model.fileNames.firstOrNull())
        }

        downloadExtraArchives(context, model, onProgress, onStatus)
    }

    private fun downloadExtraArchives(
        context: Context,
        model: ModelItem,
        onProgress: (DownloadProgress) -> Unit,
        onStatus: (String) -> Unit
    ) {
        if (model.extraArchives.isEmpty()) return

        for (archive in model.extraArchives) {
            val marker = File(model.localPath, archive.markerRelativePath)
            if (marker.exists() && (!marker.isDirectory || marker.walkTopDown().any { it.isFile })) {
                onStatus("${model.id}: ${archive.displayName} 已存在，跳过")
                continue
            }

            if (tryExtractArchiveFromAssets(context, archive, model, onStatus)) {
                onStatus("正在解压 ${model.id}: ${archive.displayName} (assets) ...")
                continue
            }

            if (archive.remoteUrl.isBlank()) {
                throw IOException("Missing remote URL and assets for ${model.id}: ${archive.displayName}")
            }

            val tempFile = File(context.cacheDir, "${model.id}-${archive.displayName}.download")
            onStatus("正在下载 ${model.id}: ${archive.displayName} ...")
            downloadFile(model.id, archive.displayName, archive.remoteUrl, tempFile, onProgress)

            onStatus("正在解压 ${model.id}: ${archive.displayName} ...")
            unzipToDirectory(tempFile, File(model.localPath))
            tempFile.delete()
        }
    }

    private fun tryExtractArchiveFromAssets(
        context: Context,
        archive: ExtraArchive,
        model: ModelItem,
        onStatus: (String) -> Unit
    ): Boolean {
        val assetPath = archive.assetPath ?: return false
        return try {
            val tempFile = File(context.cacheDir, "${model.id}-${archive.displayName}.asset.download")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            onStatus("正在解压 ${model.id}: ${archive.displayName} (assets) ...")
            unzipToDirectory(tempFile, File(model.localPath))
            tempFile.delete()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun downloadFile(
        modelId: String,
        fileName: String,
        url: String,
        tempFile: File,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: ${response.code} ${response.message}")
            }

            val body = response.body ?: throw IOException("Empty response body")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L

            body.byteStream().use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(
                                    DownloadProgress(
                                        modelId = modelId,
                                        fileName = fileName,
                                        percent = percent,
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes
                                    )
                                )
                            }
                        }
                    }
                    outputStream.flush()
                }
            }
        }
    }

    private fun saveToTarget(model: ModelItem, tempFile: File, overrideFileName: String?) {
        val dir = File(model.localPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val targetName = overrideFileName ?: model.fileNames.firstOrNull() ?: tempFile.name
        val target = File(dir, targetName)
        tempFile.copyTo(target, overwrite = true)
        tempFile.delete()
    }

    private fun unzipToDirectory(zipFile: File, destDir: File) {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }
        val destDirCanonical = destDir.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(destDir, entry.name)
                val canonicalPath = newFile.canonicalPath
                if (!canonicalPath.startsWith(destDirCanonical)) {
                    throw SecurityException("Zip entry is outside of the target dir")
                }
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

class ModelDownloadViewModel : ViewModel() {
    private val manager = ModelDownloadManager()
    private var started = false

    var uiState by mutableStateOf(DownloadUiState())
        private set

    fun checkAndDownload(context: Context) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                manager.ensureAllModels(
                    context = context,
                    onProgress = { progress ->
                        viewModelScope.launch {
                            uiState = DownloadUiState(
                                visible = true,
                                title = "正在下载 ${progress.modelId}: ${progress.fileName}...",
                                percent = progress.percent,
                                statusText = "${progress.percent}%"
                            )
                        }
                    },
                    onStatus = { status ->
                        viewModelScope.launch {
                            uiState = if (status.contains("正在下载") || status.contains("正在解压") || status.contains("正在保存")) {
                                uiState.copy(visible = true, title = status, statusText = "")
                            } else {
                                uiState.copy(visible = false, title = status, statusText = "")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                uiState = DownloadUiState(
                    visible = false,
                    title = "下载失败: ${e.message}",
                    percent = 0,
                    statusText = ""
                )
            } finally {
                uiState = uiState.copy(visible = false)
            }
        }
    }
}

@Composable
fun ModelDownloadOverlay(state: DownloadUiState) {
    if (!state.visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x80000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium
                )
                LinearProgressIndicator(
                    progress = state.percent / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.statusText.isNotEmpty()) {
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
