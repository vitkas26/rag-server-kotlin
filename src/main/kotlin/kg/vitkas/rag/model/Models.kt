package kg.vitkas.rag.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

data class Section(
    val number: Int,
    val title: String,
    val content: String
)

data class Chunk(
    val chunkId: String,
    val source: String,
    val strategy: String,
    val title: String,
    val section: String,
    val wordCount: Int,
    val content: String,
    val embedding: List<Double> = emptyList()
)

@Serializable
data class OllamaRequest(val model: String, val prompt: String)

@Serializable
data class OllamaResponse(
    val embedding: List<Double>? = null,   // старый /api/embeddings
    val embeddings: List<List<Double>>? = null  // новый /api/embed
) {
    fun toVector(): List<Double> =
        embedding ?: embeddings?.firstOrNull() ?: emptyList()
}

@Serializable
data class OllamaChatMessage(val role: String, val content: String)

@Serializable
data class OllamaChatOptions(
    val temperature: Double? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    @SerialName("num_ctx") val numCtx: Int? = null,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("repeat_penalty") val repeatPenalty: Double? = null,
    val seed: Int? = null
)

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = false,
    val options: OllamaChatOptions? = null,
    // Ollama constrained-output: "json" гарантирует валидный JSON-синтаксис ответа,
    // но НЕ гарантирует дословность цитат внутри него и НЕ проверяет структуру полей —
    // это просто грамматическое ограничение генерации, а не JSON Schema.
    val format: JsonElement? = null
)

@Serializable
data class OllamaChatResponseMessage(val role: String = "assistant", val content: String = "")

@Serializable
data class OllamaChatResponse(
    val message: OllamaChatResponseMessage? = null,
    val done: Boolean = true,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null
)

@Serializable
data class AnthropicMessage(val role: String, val content: String)

@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>
)

@Serializable
data class AnthropicContentBlock(val type: String, val text: String? = null)

@Serializable
data class AnthropicResponse(val content: List<AnthropicContentBlock> = emptyList()) {
    fun text(): String = content.firstOrNull { it.type == "text" }?.text ?: ""
}
