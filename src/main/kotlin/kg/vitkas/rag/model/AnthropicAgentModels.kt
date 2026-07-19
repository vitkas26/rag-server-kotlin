package kg.vitkas.rag.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire-формат Anthropic Messages API с tools (function calling) — отдельно от простых
// DTO в Models.kt, т.к. content-блок покрывает text/tool_use/tool_result варианты сразу.

// type без дефолта: shared HttpClient's Json настроен с encodeDefaults=false (см. Application.kt,
// не трогаем — переиспользуется Ollama/старым Anthropic complete()), поэтому поле-с-дефолтом
// молча пропадало бы из тела запроса. Без дефолта kotlinx.serialization всегда его сериализует.
@Serializable
data class AnthropicToolInputSchema(
    val type: String,
    val properties: JsonObject,
    val required: List<String> = emptyList()
)

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: AnthropicToolInputSchema
)

@Serializable
data class AnthropicAgentContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null,
    @SerialName("is_error") val isError: Boolean? = null
)

@Serializable
data class AnthropicAgentMessage(
    val role: String,
    val content: List<AnthropicAgentContentBlock>
)

@Serializable
data class AnthropicAgentRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val tools: List<AnthropicTool>,
    val messages: List<AnthropicAgentMessage>
)

@Serializable
data class AnthropicAgentResponse(
    val content: List<AnthropicAgentContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null
)
