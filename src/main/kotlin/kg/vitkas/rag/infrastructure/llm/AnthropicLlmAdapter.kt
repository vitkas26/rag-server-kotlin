package kg.vitkas.rag.infrastructure.llm

import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.AnthropicClient

class AnthropicLlmAdapter(private val anthropicClient: AnthropicClient) : LlmPort {
    override suspend fun complete(system: String, userMessage: String): String =
        anthropicClient.complete(system, userMessage).getOrElse { e ->
            throw RagError.AnthropicError("LLM completion failed: ${e.message}", e)
        }
}
