package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kg.vitkas.rag.model.ContextSizeResponse
import kg.vitkas.rag.model.ErrorResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.OllamaGenerationClient
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.server.routes.DebugRoutes")

// зеркалит дефолты AskRerankedRequest (topK=8, threshold=0.55f) — тот же retrieval, что у /ask-local
private const val DEFAULT_TOP_K = 8
private const val DEFAULT_THRESHOLD = 0.55f

fun Route.debugRoutes(
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    ollamaGenerationClient: OllamaGenerationClient
) {
    // Реальный размер промпта (в токенах Ollama), который получит /ask-local для этого вопроса —
    // чтобы подбирать num_ctx по факту, а не на глаз.
    get("/debug/context-size") {
        val question = call.request.queryParameters["question"]
        if (question.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("query param 'question' is required"))
            return@get
        }
        val topK = (call.request.queryParameters["topK"]?.toIntOrNull() ?: DEFAULT_TOP_K).coerceIn(1, 20)
        val threshold = call.request.queryParameters["threshold"]?.toFloatOrNull() ?: DEFAULT_THRESHOLD

        val rewritten = ollamaGenerationClient.complete(QUERY_REWRITE_SYSTEM_PROMPT, question, 100)
            .getOrElse { e -> throw RagError.OllamaGenerationError("Failed to rewrite query: ${e.message}", e) }
            .trim()

        val queryEmbedding = embeddingService.embedQuery(rewritten).getOrElse { e ->
            throw RagError.EmbeddingError("Failed to embed query: ${e.message}", e)
        }

        val results = repo.search(queryEmbedding, "chunks_by_section", topK)
        val filtered = results.filter { it.score >= threshold }

        val userMessage = buildRagUserMessage(question, filtered)

        val promptEvalCount = ollamaGenerationClient.measureContextTokens(DAY24_SYSTEM_PROMPT, userMessage)
            .getOrElse { e -> throw RagError.OllamaGenerationError("Failed to measure context size: ${e.message}", e) }

        logger.info(
            "🔵 RAG_DAY29 [CONTEXT_SIZE] question={} chunksUsed={} promptEvalCount={}",
            question, filtered.size, promptEvalCount
        )

        call.respond(
            HttpStatusCode.OK,
            ContextSizeResponse(
                promptEvalCount = promptEvalCount,
                questionLength = question.length,
                chunksUsed = filtered.size
            )
        )
    }
}
