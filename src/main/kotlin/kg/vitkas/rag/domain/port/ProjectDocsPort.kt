package kg.vitkas.rag.domain.port

import kg.vitkas.rag.domain.model.DocChunk

interface ProjectDocsPort {
    suspend fun search(query: String): List<DocChunk>
}
