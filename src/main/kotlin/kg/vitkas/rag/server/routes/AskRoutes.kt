package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.model.AskNoRagRequest
import kg.vitkas.rag.model.AskNoRagResponse
import kg.vitkas.rag.model.AskRequest
import kg.vitkas.rag.model.AskResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.model.Source
import kg.vitkas.rag.pipeline.AnthropicClient
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.server.routes.AskRoutes")

private const val RAG_SYSTEM_PROMPT = """Ты AI-ассистент ментора по системе Сюцай (методология Жаната Кожамжарова). Отвечай ТОЛЬКО на основе предоставленного контекста из базы знаний. Если ответа нет в контексте — скажи об этом явно. В конце ответа всегда указывай источники: названия разделов из которых взята информация."""

private const val NO_RAG_SYSTEM_PROMPT = """Ты AI-ассистент ментора по системе Сюцай — авторской методологии Жаната Кожамжарова. Отвечай на вопросы по нумерологии Сюцай."""

fun Route.askRoutes(
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    anthropicClient: AnthropicClient,
    config: AppConfig
) {
    post("/ask") {
        val req  = call.receive<AskRequest>()
        val topK = req.topK.coerceIn(1, 20)

        val queryEmbedding = embeddingService.embedQuery(req.question).getOrElse { e ->
            throw RagError.EmbeddingError("Failed to embed query: ${e.message}", e)
        }

        val results = repo.search(queryEmbedding, "chunks_by_section", topK)
        if (results.isEmpty()) {
            throw RagError.NotIndexedError("No chunks found — run POST /index first")
        }

        val context = results.joinToString("\n\n---\n\n") { "[${it.title}]\n${it.content}" }
        val userMessage = "Контекст из базы знаний:\n$context\n\nВопрос: ${req.question}"

        val answer = anthropicClient.complete(RAG_SYSTEM_PROMPT, userMessage).getOrElse { e ->
            throw RagError.AnthropicError("Failed to get answer from Anthropic: ${e.message}", e)
        }

        val sources = results.map { Source(chunkId = it.chunkId, title = it.title, score = it.score) }
        logger.info(
            "🔵 RAG_DAY22 [RAG] question={} sources=[{}]",
            req.question,
            sources.joinToString(", ") { it.chunkId }
        )

        call.respond(HttpStatusCode.OK, AskResponse(answer = answer, sources = sources, mode = "rag"))
    }

    post("/ask-no-rag") {
        val req = call.receive<AskNoRagRequest>()

        val answer = anthropicClient.complete(NO_RAG_SYSTEM_PROMPT, req.question).getOrElse { e ->
            throw RagError.AnthropicError("Failed to get answer from Anthropic: ${e.message}", e)
        }

        logger.info("🔵 RAG_DAY22 [NO_RAG] question={}", req.question)

        call.respond(HttpStatusCode.OK, AskNoRagResponse(answer = answer, mode = "no_rag"))
    }
}
