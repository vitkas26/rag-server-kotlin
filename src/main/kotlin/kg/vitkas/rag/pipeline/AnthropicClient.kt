package kg.vitkas.rag.pipeline

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.model.AnthropicAgentMessage
import kg.vitkas.rag.model.AnthropicAgentRequest
import kg.vitkas.rag.model.AnthropicAgentResponse
import kg.vitkas.rag.model.AnthropicMessage
import kg.vitkas.rag.model.AnthropicRequest
import kg.vitkas.rag.model.AnthropicResponse
import kg.vitkas.rag.model.AnthropicTool
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.pipeline.AnthropicClient")

class AnthropicClient(private val client: HttpClient, private val config: AppConfig) {

    suspend fun complete(
        system: String,
        userMessage: String,
        maxTokens: Int = config.anthropic.maxTokens,
        // null → берём ключ из конфига (env ANTHROPIC_API_KEY / application.conf). Задан —
        // используется только для этого вызова, конфиг не трогаем (per-request override
        // из веб-морды, см. заголовок X-Anthropic-Api-Key в AskRoutes.kt).
        apiKeyOverride: String? = null
    ): Result<String> = runCatching {
        logger.debug("Calling Anthropic API, model={}", config.anthropic.model)
        val httpResponse = client.post(config.anthropic.url) {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKeyOverride ?: config.anthropic.apiKey)
            header("anthropic-version", config.anthropic.version)
            setBody(
                AnthropicRequest(
                    model = config.anthropic.model,
                    maxTokens = maxTokens,
                    system = system,
                    messages = listOf(AnthropicMessage(role = "user", content = userMessage))
                )
            )
        }
        check(httpResponse.status.isSuccess()) {
            "Anthropic API returned ${httpResponse.status}: ${httpResponse.bodyAsText()}"
        }
        httpResponse.body<AnthropicResponse>().text()
    }

    // Day 34: агентный вызов с tools (function calling) — отдельный метод, не трогает
    // complete() выше, чтобы не сломать Day 21-33 вызовы. Тот же HttpClient/URL/заголовки.
    // model — отдельный параметр (не config.anthropic.model): агентная задача (планировать
    // несколько шагов tool-use в бюджет 10 итераций и при этом точно цитировать реальный код)
    // требует более сильную модель, чем Haiku, настроенный под короткие /help-ответы.
    suspend fun completeWithTools(
        system: String,
        messages: List<AnthropicAgentMessage>,
        tools: List<AnthropicTool>,
        maxTokens: Int = config.anthropic.maxTokens,
        model: String = config.anthropic.model
    ): Result<AnthropicAgentResponse> = runCatching {
        logger.debug("Calling Anthropic API with tools, model={}", model)
        val httpResponse = client.post(config.anthropic.url) {
            contentType(ContentType.Application.Json)
            header("x-api-key", config.anthropic.apiKey)
            header("anthropic-version", config.anthropic.version)
            setBody(
                AnthropicAgentRequest(
                    model = model,
                    maxTokens = maxTokens,
                    system = system,
                    tools = tools,
                    messages = messages
                )
            )
        }
        check(httpResponse.status.isSuccess()) {
            "Anthropic API returned ${httpResponse.status}: ${httpResponse.bodyAsText()}"
        }
        httpResponse.body<AnthropicAgentResponse>()
    }
}
