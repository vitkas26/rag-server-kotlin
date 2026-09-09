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

// cacheControl нулевой по умолчанию и ОСТАЁТСЯ null для нецелевых тулов — это не landmine
// encodeDefaults (см. комментарий выше): null == дефолт, поэтому пропуск поля здесь и есть
// желаемое поведение (нет cache_control → не кэшируем). Landmine только у полей, чьё
// единственное реальное значение совпадает с ненулевым дефолтом (см. AnthropicCacheControl.type
// и AnthropicSystemBlock.type ниже — у них дефолта нет специально).
@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: AnthropicToolInputSchema,
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null
)

@Serializable
data class AnthropicCacheControl(val type: String)

@Serializable
data class AnthropicSystemBlock(
    val type: String,
    val text: String,
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null
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
    val system: List<AnthropicSystemBlock>,
    val tools: List<AnthropicTool>,
    val messages: List<AnthropicAgentMessage>
)

@Serializable
data class AnthropicAgentResponse(
    val content: List<AnthropicAgentContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null
)

// Prompt caching (Day 34.1): system-промпт и список tools не меняются между итерациями
// одного agent loop (см. FileAssistantUseCase) — идеальный кандидат для Anthropic
// prompt caching. Брейкпоинт на последнем system-блоке по документации Anthropic кэширует
// ПРЕФИКС целиком (tools рендерятся перед system, поэтому уже покрыты одним брейкпоинтом);
// брейкпоинт на последнем tool добавлен ДОПОЛНИТЕЛЬНО, по заданию — избыточен относительно
// system-брейкпоинта, но не вреден (лимит Anthropic — 4 брейкпоинта на запрос, используем 2).
// НЕ проверено живым вызовом (баланс API исчерпан на момент реализации) — формат соответствует
// документации (shared/prompt-caching.md), корректность wire-shape проверена юнит-тестом
// сериализации (AnthropicAgentModelsTest), но не подтверждена реальным API-ответом с
// cache_creation_input_tokens/cache_read_input_tokens.
fun buildCachedAgentRequest(
    model: String,
    maxTokens: Int,
    system: String,
    tools: List<AnthropicTool>,
    messages: List<AnthropicAgentMessage>
): AnthropicAgentRequest {
    val cachedTools = if (tools.isEmpty()) {
        tools
    } else {
        tools.mapIndexed { index, tool ->
            if (index == tools.lastIndex) tool.copy(cacheControl = AnthropicCacheControl(type = "ephemeral")) else tool
        }
    }
    return AnthropicAgentRequest(
        model = model,
        maxTokens = maxTokens,
        system = listOf(
            AnthropicSystemBlock(type = "text", text = system, cacheControl = AnthropicCacheControl(type = "ephemeral"))
        ),
        tools = cachedTools,
        messages = messages
    )
}
