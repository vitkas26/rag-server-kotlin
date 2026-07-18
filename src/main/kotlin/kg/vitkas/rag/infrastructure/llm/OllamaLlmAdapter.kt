package kg.vitkas.rag.infrastructure.llm

import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.OllamaGenerationClient

class OllamaLlmAdapter(private val ollamaGenerationClient: OllamaGenerationClient) : LlmPort {
    override suspend fun complete(system: String, userMessage: String): String =
        ollamaGenerationClient.complete(system, userMessage).getOrElse { e ->
            throw RagError.OllamaGenerationError("LLM completion failed: ${e.message}", e)
        }
}
