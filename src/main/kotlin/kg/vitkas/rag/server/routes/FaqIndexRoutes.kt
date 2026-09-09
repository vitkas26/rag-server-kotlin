package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.infrastructure.rag.FAQ_TABLE
import kg.vitkas.rag.model.FaqIndexResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.FaqIndexingSource
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.chunkBySection
import kg.vitkas.rag.pipeline.extractSections

fun Route.faqIndexRoutes(
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    config: AppConfig
) {
    post("/index-faq") {
        val start = System.currentTimeMillis()

        val documents = FaqIndexingSource(config.faq.path).loadDocuments().getOrElse { e ->
            throw RagError.FaqIndexError("FAQ loading failed: ${e.message}", e)
        }

        val chunks = documents.flatMapIndexed { docIndex, doc ->
            val sections = extractSections(doc.text)
            chunkBySection(sections, config.rag, source = doc.path)
                .map { it.copy(chunkId = "${docIndex}_${it.chunkId}") }
        }

        val embedded = embeddingService.embedChunks(chunks).getOrElse { e ->
            throw RagError.EmbeddingError("FAQ embedding failed: ${e.message}", e)
        }

        repo.saveToTable(embedded, FAQ_TABLE).getOrElse { e ->
            throw RagError.DatabaseError("FAQ DB save failed: ${e.message}", e)
        }

        call.respond(
            HttpStatusCode.OK,
            FaqIndexResponse(chunks = embedded.size, durationMs = System.currentTimeMillis() - start)
        )
    }
}
