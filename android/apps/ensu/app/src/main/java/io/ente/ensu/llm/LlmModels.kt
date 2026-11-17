package io.ente.ensu.llm

internal fun automaticMaxOutputTokens(contextLength: Int): Int =
    minOf(2048, maxOf(1, contextLength / 4))

data class LlmModelSelection(
    val id: String,
    val contextLength: Int? = null,
    val customModelPath: String? = null,
) {
    private val isSmallModel: Boolean
        get() = id == "lfm-350m"

    private val isCustomModel: Boolean
        get() = customModelPath != null

    private val isGemmaModel: Boolean
        get() = id == "gemma-4-e2b-q4km"

    val batchSize: Int
        get() = when {
            isSmallModel -> 128
            isCustomModel || isGemmaModel -> 256
            else -> 512
        }

    fun contextCandidates(): List<Int> {
        val defaultContext =
            when {
                isSmallModel -> 2048
                isCustomModel -> 4096
                isGemmaModel -> 8192
                else -> 12000
            }
        val requested = contextLength?.takeIf { it > 0 } ?: defaultContext
        val desired =
            if (isSmallModel || isGemmaModel) minOf(requested, defaultContext) else requested
        return (listOf(desired) + listOf(12000, 8192, 4096, 2048, 1024)).distinct().filter {
            it <= desired
        }
    }
}

data class DownloadProgress(
    val percent: Int?,
    val status: String,
    val phase: DownloadPhase = DownloadPhase.Downloading,
)

enum class DownloadPhase {
    Downloading,
    Loading,
    Ready,
    Failed,
}

enum class LlmMessageRole {
    User,
    Assistant,
    System,
}

data class LlmMessage(
    val text: String,
    val role: LlmMessageRole,
    val hasAttachments: Boolean = false,
)

data class GenerationSummary(
    val jobId: Long,
    val generatedTokens: Int,
    val totalTimeMs: Long?,
)
