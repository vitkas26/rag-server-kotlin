package kg.vitkas.rag.infrastructure.llm

import kg.vitkas.rag.domain.model.AgentContentBlock
import kg.vitkas.rag.domain.model.AgentMessage
import kg.vitkas.rag.domain.model.AgentTurn
import kg.vitkas.rag.domain.model.ToolDefinition
import kg.vitkas.rag.domain.port.AgenticLlmPort
import kg.vitkas.rag.model.AnthropicAgentContentBlock
import kg.vitkas.rag.model.AnthropicAgentMessage
import kg.vitkas.rag.model.AnthropicTool
import kg.vitkas.rag.model.AnthropicToolInputSchema
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.AnthropicClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// implements AgenticLlmPort — транслирует провайдер-независимые domain-модели агентного
// цикла (AgentMessage/AgentContentBlock/ToolDefinition) в wire-формат Anthropic tools API
// и обратно. Переиспользует общий pipeline/AnthropicClient.kt (метод completeWithTools),
// старый LlmPort/AnthropicLlmAdapter (Day 21-33) не трогает.
class AnthropicAgenticLlmAdapter(
    private val anthropicClient: AnthropicClient,
    // write_file требует прислать ПОЛНОЕ новое содержимое файла как tool input — общий
    // config.anthropic.maxTokens (1024, тюненный под короткие /help-ответы) для этого мал:
    // модель обрывает генерацию и вместо tool_use отдаёт только текстовый план. Агентный
    // путь использует свой, больший бюджет, не трогая конфиг остальных Day 21-33 вызовов.
    private val maxTokens: Int = 4096,
    // Агентная задача — спланировать несколько шагов tool-use в бюджет 10 итераций (см.
    // FileAssistantUseCase.MAX_ITERATIONS) и при этом точно цитировать реально прочитанный
    // код — Haiku (config.anthropic.model, тюненный под короткие /help-ответы) на этом
    // либо не укладывается в бюджет, либо галлюцинирует детали. Не трогает config.anthropic.model.
    private val model: String = "claude-sonnet-4-5"
) : AgenticLlmPort {

    override suspend fun sendTurn(
        system: String,
        history: List<AgentMessage>,
        tools: List<ToolDefinition>
    ): AgentTurn {
        val wireMessages = history.map { message ->
            AnthropicAgentMessage(role = message.role, content = message.content.map { it.toWire() })
        }
        val wireTools = tools.map { it.toWire() }

        val response = anthropicClient.completeWithTools(system, wireMessages, wireTools, maxTokens, model)
            .getOrElse { e -> throw RagError.AnthropicError("Agentic LLM call failed: ${e.message}", e) }

        return if (response.stopReason == "tool_use") {
            AgentTurn.ToolCallRequested(response.content.map { it.toDomain() })
        } else {
            val text = response.content.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
            AgentTurn.FinalAnswer(text)
        }
    }

    private fun ToolDefinition.toWire(): AnthropicTool {
        val properties = buildJsonObject {
            params.forEach { param ->
                putJsonObject(param.name) {
                    put("type", "string")
                    put("description", param.description)
                }
            }
        }
        val required = params.filter { it.required }.map { it.name }
        return AnthropicTool(
            name = name,
            description = description,
            inputSchema = AnthropicToolInputSchema(type = "object", properties = properties, required = required)
        )
    }

    private fun AgentContentBlock.toWire(): AnthropicAgentContentBlock = when (this) {
        is AgentContentBlock.Text -> AnthropicAgentContentBlock(type = "text", text = text)
        is AgentContentBlock.ToolUse -> AnthropicAgentContentBlock(
            type = "tool_use",
            id = id,
            name = name,
            input = buildJsonObject { input.forEach { (key, value) -> put(key, value) } }
        )
        is AgentContentBlock.ToolResult -> AnthropicAgentContentBlock(
            type = "tool_result",
            toolUseId = toolUseId,
            content = content,
            isError = if (isError) true else null
        )
    }

    private fun AnthropicAgentContentBlock.toDomain(): AgentContentBlock = when (type) {
        "tool_use" -> AgentContentBlock.ToolUse(
            id = id ?: "",
            name = name ?: "",
            input = input?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() } ?: emptyMap()
        )
        else -> AgentContentBlock.Text(text ?: "")
    }
}
