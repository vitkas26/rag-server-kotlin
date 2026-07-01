package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.model.SearchRequest
import kg.vitkas.rag.model.SearchResponse
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository

fun Route.searchRoutes(
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    config: AppConfig
) {
    post("/search") {
        val req   = call.receive<SearchRequest>()
        val table = if (req.strategy == "fixed_size") "chunks_fixed" else "chunks_by_section"
        val topK  = req.topK.coerceIn(1, 20)

        val queryEmbedding = embeddingService.embedQuery(req.query).getOrElse { e ->
            throw RagError.EmbeddingError("Failed to embed query: ${e.message}", e)
        }

        val results = repo.search(queryEmbedding, table, topK)

        if (results.isEmpty()) {
            throw RagError.NotIndexedError("No chunks found in $table — run POST /index first")
        }

        call.respond(HttpStatusCode.OK, SearchResponse(results = results, count = results.size))
    }
}
