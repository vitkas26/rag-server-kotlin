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
import kotlinx.serialization.json.JsonElement
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
        maxTokens: Int = 1024,
        options: OllamaChatOptions? = null,
        modelOverride: String? = null,
        format: JsonElement? = null
    ): Result<String> = runCatching {
        val effectiveModel = modelOverride ?: model
        logger.debug("Calling Ollama chat API, model={}", effectiveModel)
        val response = postChat(
            OllamaChatRequest(
                model = effectiveModel,
                messages = listOf(
                    OllamaChatMessage(role = "system", content = system),
                    OllamaChatMessage(role = "user", content = userMessage)
                ),
                stream = false,
                options = options ?: OllamaChatOptions(numPredict = maxTokens),
                format = format
            )
        )
        response.message?.content ?: error("Ollama chat response missing message.content")
    }

    // Дешёвый способ узнать реальное число токенов промпта (system + user) через Ollama:
    // num_predict=1 обрезает генерацию, но prompt_eval_count в ответе всё равно посчитан по полному контексту.
    suspend fun measureContextTokens(system: String, userMessage: String): Result<Int> = runCatching {
        logger.debug("Measuring context size via Ollama chat API, model={}", model)
        val response = postChat(
            OllamaChatRequest(
                model = model,
                messages = listOf(
                    OllamaChatMessage(role = "system", content = system),
                    OllamaChatMessage(role = "user", content = userMessage)
                ),
                stream = false,
                options = OllamaChatOptions(numPredict = 1)
            )
        )
        response.promptEvalCount ?: error("Ollama chat response missing prompt_eval_count")
    }

    private suspend fun postChat(request: OllamaChatRequest): OllamaChatResponse {
        val body = requestJson.encodeToString(OllamaChatRequest.serializer(), request)
        logger.debug("Ollama chat request body: {}", body)
        val httpResponse = client.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(httpResponse.status.isSuccess()) {
            "Ollama chat API returned ${httpResponse.status}: ${httpResponse.bodyAsText()}"
        }
        return httpResponse.body()
    }
}
