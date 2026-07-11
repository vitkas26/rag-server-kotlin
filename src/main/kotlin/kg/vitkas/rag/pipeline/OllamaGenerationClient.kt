package kg.vitkas.rag.pipeline

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kg.vitkas.rag.model.OllamaChatMessage
import kg.vitkas.rag.model.OllamaChatOptions
import kg.vitkas.rag.model.OllamaChatRequest
import kg.vitkas.rag.model.OllamaChatResponse
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.pipeline.OllamaGenerationClient")

// encodeDefaults=true — иначе "stream": false (значение по умолчанию) не попадёт в JSON,
// Ollama решит что stream не передан и вернёт NDJSON вместо одного объекта.
private val requestJson = Json { encodeDefaults = true }

class OllamaGenerationClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val model: String
) {

    suspend fun complete(
        system: String,
        userMessage: String,
        maxTokens: Int = 1024
    ): Result<String> = runCatching {
        logger.debug("Calling Ollama chat API, model={}", model)
        val body = requestJson.encodeToString(
            OllamaChatRequest.serializer(),
            OllamaChatRequest(
                model = model,
                messages = listOf(
                    OllamaChatMessage(role = "system", content = system),
                    OllamaChatMessage(role = "user", content = userMessage)
                ),
                stream = false,
                options = OllamaChatOptions(numPredict = maxTokens)
            )
        )
        val httpResponse = client.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(httpResponse.status.isSuccess()) {
            "Ollama chat API returned ${httpResponse.status}: ${httpResponse.bodyAsText()}"
        }
        httpResponse.body<OllamaChatResponse>().message?.content
            ?: error("Ollama chat response missing message.content")
    }
}
