package io.ente.ensu.llm

import kotlinx.serialization.Serializable

@Serializable
data class CustomModel(
    val id: String,
    val name: String,
    val path: String,
    val sourceUrl: String,
)

data class ModelSettingsState(
    val modelId: String = "",
    val contextLength: String = "",
    val temperature: String = "",
    val isSaving: Boolean = false,
    val customModels: List<CustomModel> = emptyList(),
)
