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
import kg.vitkas.rag.model.AnthropicMessage
import kg.vitkas.rag.model.AnthropicRequest
import kg.vitkas.rag.model.AnthropicResponse
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.pipeline.AnthropicClient")

class AnthropicClient(private val client: HttpClient, private val config: AppConfig) {

    suspend fun complete(
        system: String,
        userMessage: String,
        maxTokens: Int = config.anthropic.maxTokens
    ): Result<String> = runCatching {
        logger.debug("Calling Anthropic API, model={}", config.anthropic.model)
        val httpResponse = client.post(config.anthropic.url) {
            contentType(ContentType.Application.Json)
            header("x-api-key", config.anthropic.apiKey)
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
}
