package kg.vitkas.rag.infrastructure.llm

import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.model.OllamaChatOptions
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.OllamaGenerationClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Structured output (Ollama format = JSON Schema) вместо хрупкого текстового парсинга
// ## Баги / ## Архитектурные проблемы / ## Рекомендации — qwen2.5:7b-instruct на CPU
// не всегда воспроизводит точные markdown-заголовки, а JSON Schema форсирует структуру
// на уровне самой Ollama, а не полагается на дисциплину модели.
private val REVIEW_JSON_SCHEMA = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("bugs") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("architectureIssues") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("recommendations") {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
    }
    put("required", buildJsonArray {
        add(JsonPrimitive("bugs"))
        add(JsonPrimitive("architectureIssues"))
        add(JsonPrimitive("recommendations"))
    })
}

// Дефолт Ollama (обычно 2048/4096) не даёт запаса под system-промпт + diff + RAG-контекст —
// явно фиксируем с запасом, вместо null (который отдаёт выбор дефолта самой Ollama).
private const val REVIEW_NUM_CTX = 8192
private const val REVIEW_MAX_TOKENS = 1024

class OllamaLlmAdapter(private val ollamaGenerationClient: OllamaGenerationClient) : LlmPort {
    override suspend fun complete(system: String, userMessage: String): String =
        ollamaGenerationClient.complete(
            system = system,
            userMessage = userMessage,
            options = OllamaChatOptions(numPredict = REVIEW_MAX_TOKENS, numCtx = REVIEW_NUM_CTX),
            format = REVIEW_JSON_SCHEMA
        ).getOrElse { e ->
            throw RagError.OllamaGenerationError("LLM completion failed: ${e.message}", e)
        }
}
