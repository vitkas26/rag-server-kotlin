package kg.vitkas.rag.infrastructure.rag

import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.domain.model.DocChunk
import kg.vitkas.rag.domain.port.ProjectDocsPort
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository

const val DOCS_TABLE = "docs_chunks"

class ProjectDocsRagAdapter(
    private val embeddingService: EmbeddingService,
    private val repo: IndexRepository,
    private val config: AppConfig
) : ProjectDocsPort {

    override suspend fun search(query: String): List<DocChunk> {
        val queryEmbedding = embeddingService.embedQuery(query).getOrElse { e ->
            throw RagError.EmbeddingError("Docs query embedding failed: ${e.message}", e)
        }
        return repo.searchChunksWithScore(queryEmbedding, DOCS_TABLE, config.rag.topK)
            .map { (chunk, score) -> DocChunk(content = chunk.content, source = chunk.source, score = score) }
    }
}
