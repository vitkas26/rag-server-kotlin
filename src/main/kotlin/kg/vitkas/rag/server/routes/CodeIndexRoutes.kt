package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.infrastructure.rag.CODE_TABLE
import kg.vitkas.rag.model.DocsIndexResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.CodeIndexingSource
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.chunkByFixedSize

fun Route.codeIndexRoutes(
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    config: AppConfig
) {
    post("/index-code") {
        val start = System.currentTimeMillis()

        val documents = CodeIndexingSource().loadDocuments().getOrElse { e ->
            throw RagError.DocsIndexError("Code loading failed: ${e.message}", e)
        }

        // Тот же паттерн уникальности chunk_id, что и в DocsIndexRoutes (Day 31 баг) —
        // chunkByFixedSize нумерует чанки с нуля на каждый вызов, префикс индексом документа
        // не даёт id пересечься между разными .kt-файлами в общей таблице code_chunks.
        val chunks = documents.flatMapIndexed { docIndex, doc ->
            chunkByFixedSize(doc.text, config.rag, source = doc.path)
                .map { it.copy(chunkId = "${docIndex}_${it.chunkId}") }
        }

        val embedded = embeddingService.embedChunks(chunks).getOrElse { e ->
            throw RagError.EmbeddingError("Code embedding failed: ${e.message}", e)
        }

        repo.saveToTable(embedded, CODE_TABLE).getOrElse { e ->
            throw RagError.DatabaseError("Code DB save failed: ${e.message}", e)
        }

        call.respond(
            HttpStatusCode.OK,
            DocsIndexResponse(chunks = embedded.size, durationMs = System.currentTimeMillis() - start)
        )
    }
}
