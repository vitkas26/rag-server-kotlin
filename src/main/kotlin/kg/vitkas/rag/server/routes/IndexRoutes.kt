package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.model.IndexResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.chunkByFixedSize
import kg.vitkas.rag.pipeline.chunkBySection
import kg.vitkas.rag.pipeline.extractSections
import kg.vitkas.rag.pipeline.extractTextFromMarkdown

fun Route.indexRoutes(
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    config: AppConfig
) {
    post("/index") {
        val start = System.currentTimeMillis()

        val text = extractTextFromMarkdown(config.rag.mdPath).getOrElse { e ->
            throw RagError.PdfError("Markdown extraction failed: ${e.message}", e)
        }

        val fixedChunks   = chunkByFixedSize(text, config.rag)
        val sections      = extractSections(text)
        val sectionChunks = chunkBySection(sections, config.rag)

        val embeddedFixed = embeddingService.embedChunks(fixedChunks).getOrElse { e ->
            throw RagError.EmbeddingError("Embedding failed (fixed): ${e.message}", e)
        }
        val embeddedSection = embeddingService.embedChunks(sectionChunks).getOrElse { e ->
            throw RagError.EmbeddingError("Embedding failed (section): ${e.message}", e)
        }

        repo.save(embeddedFixed, embeddedSection).getOrElse { e ->
            throw RagError.DatabaseError("DB save failed: ${e.message}", e)
        }

        call.respond(
            HttpStatusCode.OK,
            IndexResponse(
                fixedChunks   = embeddedFixed.size,
                sectionChunks = embeddedSection.size,
                durationMs    = System.currentTimeMillis() - start
            )
        )
    }
}
