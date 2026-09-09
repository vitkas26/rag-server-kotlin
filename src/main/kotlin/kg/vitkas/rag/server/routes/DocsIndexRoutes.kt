package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.infrastructure.rag.DOCS_TABLE
import kg.vitkas.rag.model.DocsIndexResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.DocsIndexingSource
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.chunkByFixedSize

fun Route.docsIndexRoutes(
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    config: AppConfig
) {
    post("/index-docs") {
        val start = System.currentTimeMillis()

        val documents = DocsIndexingSource(config.docs.paths).loadDocuments().getOrElse { e ->
            throw RagError.DocsIndexError("Docs loading failed: ${e.message}", e)
        }

        // chunkByFixedSize сбрасывает нумерацию (chunk_id="fixed_0", ...) на каждый вызов — при
        // нескольких документах id'ы пересекаются между документами, а chunk_id это PRIMARY KEY
        // в docs_chunks (saveToTable делает INSERT OR REPLACE), так что более поздний документ
        // молча затирал чанки более раннего с тем же id. Префикс индексом документа делает id
        // уникальным глобально по всей docs-коллекции.
        val chunks = documents.flatMapIndexed { docIndex, doc ->
            chunkByFixedSize(doc.text, config.rag, source = doc.path)
                .map { it.copy(chunkId = "${docIndex}_${it.chunkId}") }
        }

        val embedded = embeddingService.embedChunks(chunks).getOrElse { e ->
            throw RagError.EmbeddingError("Docs embedding failed: ${e.message}", e)
        }

        repo.saveToTable(embedded, DOCS_TABLE).getOrElse { e ->
            throw RagError.DatabaseError("Docs DB save failed: ${e.message}", e)
        }

        call.respond(
            HttpStatusCode.OK,
            DocsIndexResponse(chunks = embedded.size, durationMs = System.currentTimeMillis() - start)
        )
    }
}
