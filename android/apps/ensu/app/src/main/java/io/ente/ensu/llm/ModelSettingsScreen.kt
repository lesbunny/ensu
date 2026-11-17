package io.ente.ensu.llm

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.ente.ensu.bindings.ModelRuntimeSurface
import io.ente.ensu.bindings.resolveModelPolicy
import io.ente.ensu.designsystem.EnsuColor
import io.ente.ensu.designsystem.EnsuSpacing
import io.ente.ensu.designsystem.EnsuTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    totalMemoryBytes: Long?,
    state: ModelSettingsState,
    onSave: (ModelSettingsState) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelPolicy =
        remember(totalMemoryBytes) {
            resolveModelPolicy(
                surface = ModelRuntimeSurface.ANDROID,
                totalMemoryBytes = totalMemoryBytes?.toULong(),
            )
        }
    var customModels by remember(state) { mutableStateOf(state.customModels) }
    val alternateModel =
        if (modelPolicy.defaultModel.id == "lfm-350m") {
            ModelChoice("lfm-vl-1.6b", "LFM 2.5 VL 1.6B (Q4_0)")
        } else {
            ModelChoice("lfm-350m", "LFM 2.5 350M (Q4_0)")
        }
    val modelChoices =
        remember(modelPolicy, customModels) {
            listOf(
                ModelChoice(
                    id = DEFAULT_OPTION_ID,
                    title = modelPolicy.defaultModel.title,
                    isDefault = true,
                ),
                alternateModel,
            ) + customModels.map { model ->
                ModelChoice(id = "custom:${model.id}", title = model.name, isCustom = true)
            }
        }

    var selectedModelId by
        remember(state) { mutableStateOf(initialSelectionId(state, modelChoices)) }
    var contextLength by remember(state) { mutableStateOf(state.contextLength) }
    var temperature by remember(state) { mutableStateOf(state.temperature) }
    val contextError =
        contextLength
            .takeIf { it.isNotBlank() }
            ?.let { raw ->
                val value = raw.toIntOrNull()
                when {
                    value == null -> "Enter a valid integer"
                    value <= 256 + automaticMaxOutputTokens(value) ->
                        "Increase context length to leave room for the conversation and response."
                    else -> null
                }
            }
    var showAdvancedLimits by
        remember(state) {
            mutableStateOf(state.contextLength.isNotBlank() || state.temperature.isNotBlank())
        }
    var isModelMenuExpanded by remember { mutableStateOf(false) }

    var showAddCustomDialog by remember { mutableStateOf(false) }
    var customModelUrl by remember { mutableStateOf("") }
    var isDownloadingCustomModel by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<CustomModelDownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    val selectedModel =
        modelChoices.firstOrNull { it.id == selectedModelId } ?: modelChoices.first()
    val isCustomSelection = selectedModel.isCustom
    val usesSmallModel =
        selectedModel.id == "lfm-350m" ||
            (selectedModel.isDefault && modelPolicy.defaultModel.id == "lfm-350m")

    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(EnsuSpacing.pageHorizontal.dp)
    ) {
        SectionHeader("Select model")
        Spacer(modifier = Modifier.height(EnsuSpacing.xs.dp))
        Text(
            text = "Choose either built-in model or add a custom GGUF file.",
            style = EnsuTypography.small,
            color = EnsuColor.textMuted(),
        )
        Spacer(modifier = Modifier.height(EnsuSpacing.sm.dp))
        ExposedDropdownMenuBox(
            expanded = isModelMenuExpanded,
            onExpandedChange = { isModelMenuExpanded = !isModelMenuExpanded },
        ) {
            OutlinedTextField(
                value = selectedModel.title,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = isModelMenuExpanded)
                },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )

            ExposedDropdownMenu(
                expanded = isModelMenuExpanded,
                onDismissRequest = { isModelMenuExpanded = false },
            ) {
                modelChoices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.title) },
                        onClick = {
                            selectedModelId = choice.id
                            isModelMenuExpanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Add custom") },
                    onClick = {
                        isModelMenuExpanded = false
                        customModelUrl = ""
                        downloadError = null
                        downloadProgress = null
                        showAddCustomDialog = true
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(EnsuSpacing.lg.dp))
        ExpandButton(
            title = "Advanced limits",
            expanded = showAdvancedLimits,
            collapsedHint = "Context length and temperature",
            onToggle = { showAdvancedLimits = !showAdvancedLimits },
        )
        AnimatedVisibility(showAdvancedLimits) {
            Column {
                Spacer(modifier = Modifier.height(EnsuSpacing.sm.dp))
                Text(
                    text = "Context length",
                    style = EnsuTypography.small,
                    color = EnsuColor.textMuted(),
                )
                Spacer(modifier = Modifier.height(EnsuSpacing.xs.dp))
                OutlinedTextField(
                    value = contextLength,
                    onValueChange = { contextLength = it },
                    placeholder = {
                        Text(
                            text =
                                when {
                                    isCustomSelection -> "4096"
                                    usesSmallModel -> "2048"
                                    else -> "8192"
                                }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isError = contextError != null,
                    supportingText = { contextError?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    text =
                        when {
                            isCustomSelection ->
                                "Custom models default to 4,096 tokens. Larger contexts need more memory."
                            usesSmallModel ->
                                "Context is limited to 2,048 tokens on this device. Response length adjusts automatically."
                            else -> "Response length adjusts automatically to the context length."
                        },
                    style = EnsuTypography.small,
                    color = EnsuColor.textMuted(),
                )

                Spacer(modifier = Modifier.height(EnsuSpacing.md.dp))
                Text(
                    text = "Temperature",
                    style = EnsuTypography.small,
                    color = EnsuColor.textMuted(),
                )
                Spacer(modifier = Modifier.height(EnsuSpacing.xs.dp))
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    placeholder = { Text(text = "0.7") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )

                Spacer(modifier = Modifier.height(EnsuSpacing.sm.dp))
                Text(
                    text = "Leave blank to use model defaults",
                    style = EnsuTypography.small,
                    color = EnsuColor.textMuted(),
                )
                Spacer(modifier = Modifier.height(EnsuSpacing.xs.dp))
                Text(
                    text = "Values below 0.35 or above 0.7 are clamped automatically.",
                    style = EnsuTypography.small,
                    color = EnsuColor.textMuted(),
                )
            }
        }

        Spacer(modifier = Modifier.height(EnsuSpacing.xl.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(EnsuSpacing.lg.dp))

        Button(
            enabled = contextError == null && !isDownloadingCustomModel,
            onClick = {
                val savedState =
                    state.copy(
                        modelId = selectedModel.id.takeUnless { selectedModel.isDefault }.orEmpty(),
                        contextLength = contextLength,
                        temperature = temperature,
                        customModels = customModels,
                    )
                onSave(savedState)
                Toast.makeText(context, "Model settings saved", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = EnsuColor.accent()),
        ) {
            Text(text = "Save Model Settings", style = EnsuTypography.body)
        }

        Spacer(modifier = Modifier.height(EnsuSpacing.md.dp))

        TextButton(
            onClick = {
                onReset()
                selectedModelId = DEFAULT_OPTION_ID
                contextLength = ""
                temperature = ""
                Toast.makeText(context, "Model settings reset", Toast.LENGTH_SHORT).show()
            }
        ) {
            Text(
                text = "Reset to defaults",
                style = EnsuTypography.body,
                color = EnsuColor.action(),
            )
        }

        Spacer(modifier = Modifier.height(EnsuSpacing.md.dp))
        Text(
            text = "Changes apply the next time the model loads.",
            style = EnsuTypography.small,
            color = EnsuColor.textMuted(),
        )
    }

    if (showAddCustomDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloadingCustomModel) showAddCustomDialog = false },
            title = { Text("Add custom model") },
            text = {
                Column {
                    Text(
                        text = "Paste a link to one .gguf file on Hugging Face. File links work; repository links and multi-part models do not.",
                        style = EnsuTypography.small,
                        color = EnsuColor.textMuted(),
                    )
                    Spacer(modifier = Modifier.height(EnsuSpacing.sm.dp))
                    OutlinedTextField(
                        value = customModelUrl,
                        onValueChange = {
                            customModelUrl = it
                            downloadError = null
                        },
                        enabled = !isDownloadingCustomModel,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Hugging Face file link") },
                        placeholder = {
                            Text("https://huggingface.co/user/repo/blob/main/model.gguf")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    if (isDownloadingCustomModel) {
                        Spacer(modifier = Modifier.height(EnsuSpacing.md.dp))
                        val progress = downloadProgress
                        val fraction = progress?.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = fraction,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(EnsuSpacing.xs.dp))
                        Text(
                            text = progress?.let(::downloadProgressLabel) ?: "Preparing download…",
                            style = EnsuTypography.small,
                            color = EnsuColor.textMuted(),
                        )
                    }
                    downloadError?.let { error ->
                        Spacer(modifier = Modifier.height(EnsuSpacing.sm.dp))
                        Text(text = error, style = EnsuTypography.small, color = EnsuColor.textMuted())
                    }
                    Spacer(modifier = Modifier.height(EnsuSpacing.sm.dp))
                    Text(
                        text = "The model is stored privately on this device. Large models can use several gigabytes and may not fit in available memory.",
                        style = EnsuTypography.small,
                        color = EnsuColor.textMuted(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = customModelUrl.isNotBlank() && !isDownloadingCustomModel,
                    onClick = {
                        downloadJob =
                            scope.launch {
                                isDownloadingCustomModel = true
                                downloadError = null
                                downloadProgress = null
                                try {
                                    val model =
                                        CustomModelDownloader.download(context, customModelUrl) {
                                            scope.launch { downloadProgress = it }
                                        }
                                    val updatedModels = customModels.filterNot { it.id == model.id } + model
                                    customModels = updatedModels
                                    selectedModelId = "custom:${model.id}"
                                    onSave(
                                        state.copy(
                                            modelId = selectedModelId,
                                            contextLength = contextLength,
                                            temperature = temperature,
                                            customModels = updatedModels,
                                        )
                                    )
                                    showAddCustomDialog = false
                                    customModelUrl = ""
                                    Toast.makeText(context, "Custom model downloaded and selected", Toast.LENGTH_SHORT).show()
                                } catch (error: CancellationException) {
                                    // A cancelled download leaves no selectable model behind.
                                } catch (error: Exception) {
                                    downloadError = error.message ?: "Unable to download this model."
                                } finally {
                                    isDownloadingCustomModel = false
                                    downloadJob = null
                                }
                            }
                    },
                ) {
                    if (isDownloadingCustomModel) {
                        CircularProgressIndicator()
                    } else {
                        Text("Download")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (isDownloadingCustomModel) {
                            downloadJob?.cancel()
                        } else {
                            showAddCustomDialog = false
                        }
                    }
                ) {
                    Text(if (isDownloadingCustomModel) "Cancel download" else "Cancel")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = EnsuTypography.body)
}

@Composable
private fun ExpandButton(
    title: String,
    expanded: Boolean,
    collapsedHint: String,
    onToggle: () -> Unit,
) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = title, style = EnsuTypography.body, color = EnsuColor.action())
            if (!expanded) {
                Spacer(modifier = Modifier.height(EnsuSpacing.xs.dp))
                Text(
                    text = collapsedHint,
                    style = EnsuTypography.small,
                    color = EnsuColor.textMuted(),
                )
            }
        }
    }
}

private data class ModelChoice(
    val id: String,
    val title: String,
    val isDefault: Boolean = false,
    val isCustom: Boolean = false,
)

private fun initialSelectionId(
    state: ModelSettingsState,
    choices: List<ModelChoice>,
): String {
    return choices.firstOrNull { it.id == state.modelId }?.id ?: DEFAULT_OPTION_ID
}

private fun downloadProgressLabel(progress: CustomModelDownloadProgress): String {
    val total = progress.totalBytes
    return if (total != null && total > 0) {
        "${formatBytes(progress.downloadedBytes)} / ${formatBytes(total)}"
    } else {
        "${formatBytes(progress.downloadedBytes)} downloaded"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "${"%.1f".format(value)} ${units[unit]}"
}

private const val DEFAULT_OPTION_ID = "default"
