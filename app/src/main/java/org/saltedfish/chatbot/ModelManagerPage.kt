package org.saltedfish.chatbot

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

data class ModelCardUi(
    val id: String,
    val name: String,
    val capability: ModelCapability,
    val downloaded: Boolean,
    val selected: Boolean
)

class ModelManagerViewModel : ViewModel() {
    private val manager = ModelDownloadManager()

    var models by mutableStateOf(emptyList<ModelCardUi>())
        private set

    var downloadUiState by mutableStateOf(DownloadUiState())
        private set

    var working by mutableStateOf(false)
        private set

    init {
        refreshModels()
    }

    fun refreshModels() {
        models = ModelConfig.models.map { model ->
            val selected = when (model.capability) {
                ModelCapability.TEXT -> AppConfig.selectedTextModelId == model.id
                ModelCapability.MULTIMODAL -> AppConfig.selectedVisionModelId == model.id
            }
            ModelCardUi(
                id = model.id,
                name = model.displayName,
                capability = model.capability,
                downloaded = ModelConfig.isModelDownloaded(model),
                selected = selected
            )
        }
    }

    fun selectModel(modelId: String, context: Context) {
        val model = ModelConfig.findModelById(modelId) ?: return
        if (!ModelConfig.isModelDownloaded(model)) {
            Toast.makeText(context, "Please download the model before selecting it.", Toast.LENGTH_SHORT).show()
            return
        }
        when (model.capability) {
            ModelCapability.TEXT -> {
                AppConfig.selectedTextModelId =
                    if (AppConfig.selectedTextModelId == model.id) null else model.id
            }
            ModelCapability.MULTIMODAL -> {
                AppConfig.selectedVisionModelId =
                    if (AppConfig.selectedVisionModelId == model.id) null else model.id
            }
        }
        refreshModels()
    }

    fun downloadModel(context: Context, modelId: String) {
        if (working) return
        working = true
        viewModelScope.launch {
            try {
                manager.downloadModelById(
                    context = context,
                    modelId = modelId,
                    onProgress = { progress ->
                        viewModelScope.launch {
                            downloadUiState = DownloadUiState(
                                visible = true,
                                title = "Downloading ${progress.modelId}: ${progress.fileName}...",
                                percent = progress.percent,
                                statusText = "${progress.percent}%"
                            )
                        }
                    },
                    onStatus = { status ->
                        viewModelScope.launch {
                            val isBusyStatus = status.contains("正在下载") || status.contains("正在解压") || status.contains("正在保存")
                            downloadUiState = if (isBusyStatus) {
                                downloadUiState.copy(visible = true, title = translateStatus(status), statusText = "")
                            } else {
                                downloadUiState.copy(visible = false, title = translateStatus(status), statusText = "")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                working = false
                downloadUiState = downloadUiState.copy(visible = false)
                refreshModels()
            }
        }
    }

    private fun translateStatus(status: String): String {
        return status
            .replace("正在下载", "Downloading")
            .replace("正在解压", "Extracting")
            .replace("正在保存", "Saving")
            .replace("下载链接未配置，已跳过", "download URL is not configured, skipped")
            .replace("已存在，跳过", "already exists, skipped")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerPage(
    navController: NavController,
    vm: ModelManagerViewModel = viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Model Manager") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "How to Use",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "1. Download a model first, then select it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "2. You can select at most one Text model and one Multimodal model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "3. Chat uses Text models only; Image Reader uses Multimodal models only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            val selectedTextModelName = vm.models
                .firstOrNull { it.capability == ModelCapability.TEXT && it.selected }
                ?.name ?: "Not selected"
            val selectedVisionModelName = vm.models
                .firstOrNull { it.capability == ModelCapability.MULTIMODAL && it.selected }
                ?.name ?: "Not selected"

            CapabilitySection(
                title = "Text Models",
                selectedName = selectedTextModelName,
                models = vm.models.filter { it.capability == ModelCapability.TEXT },
                vm = vm,
                context = context
            )

            CapabilitySection(
                title = "Multimodal Models",
                selectedName = selectedVisionModelName,
                models = vm.models.filter { it.capability == ModelCapability.MULTIMODAL },
                vm = vm,
                context = context
            )

            Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
        }

        ModelDownloadOverlay(vm.downloadUiState)
    }
}

@Composable
private fun CapabilitySection(
    title: String,
    selectedName: String,
    models: List<ModelCardUi>,
    vm: ModelManagerViewModel,
    context: Context
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Selected: $selectedName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider()
            if (models.isEmpty()) {
                Text(text = "No available models.", style = MaterialTheme.typography.bodySmall)
            } else {
                models.forEach { model ->
                    ModelCard(model = model, vm = vm, context = context)
                }
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelCardUi, vm: ModelManagerViewModel, context: Context) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = model.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Type: ${if (model.capability == ModelCapability.TEXT) "Text" else "Multimodal"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Status: ${if (model.downloaded) "Downloaded" else "Not downloaded"}${if (model.selected) " / Selected" else ""}",
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.downloadModel(context, model.id) },
                    enabled = !vm.working
                ) {
                    Text(if (model.downloaded) "Re-download" else "Download")
                }
                OutlinedButton(
                    onClick = { vm.selectModel(model.id, context) },
                    enabled = model.downloaded
                ) {
                    Text(
                        when {
                            model.selected -> "Unselect"
                            !model.downloaded -> "Download first"
                            else -> "Select"
                        }
                    )
                }
            }
        }
    }
}
