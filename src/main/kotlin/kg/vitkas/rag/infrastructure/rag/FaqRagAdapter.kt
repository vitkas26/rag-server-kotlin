package kg.vitkas.rag.infrastructure.rag

import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.domain.model.DocChunk
import kg.vitkas.rag.domain.port.ProjectDocsPort
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository

const val FAQ_TABLE = "faq_chunks"

class FaqRagAdapter(
    private val embeddingService: EmbeddingService,
    private val repo: IndexRepository,
    private val config: AppConfig
) : ProjectDocsPort {

    override suspend fun search(query: String): List<DocChunk> {
        val queryEmbedding = embeddingService.embedQuery(query).getOrElse { e ->
            throw RagError.EmbeddingError("FAQ query embedding failed: ${e.message}", e)
        }
        return repo.searchChunksWithScore(queryEmbedding, FAQ_TABLE, config.rag.topK)
            .map { (chunk, score) -> DocChunk(content = chunk.content, source = chunk.source, score = score) }
    }
}
