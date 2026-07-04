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
import kg.vitkas.rag.model.AskRerankedRequest
import kg.vitkas.rag.model.AskRerankedResponse
import kg.vitkas.rag.model.AskResponse
import kg.vitkas.rag.model.CompareRequest
import kg.vitkas.rag.model.CompareResponse
import kg.vitkas.rag.model.FilteredSearch
import kg.vitkas.rag.model.OriginalSearch
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.model.RerankedSearch
import kg.vitkas.rag.model.SearchResult
import kg.vitkas.rag.model.Source
import kg.vitkas.rag.pipeline.AnthropicClient
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.server.routes.AskRoutes")

private const val RAG_SYSTEM_PROMPT = """Ты AI-ассистент ментора по системе Сюцай (методология Жаната Кожамжарова). Отвечай ТОЛЬКО на основе предоставленного контекста из базы знаний. Если ответа нет в контексте — скажи об этом явно. В конце ответа всегда указывай источники: названия разделов из которых взята информация."""

private const val NO_RAG_SYSTEM_PROMPT = """Ты AI-ассистент ментора по системе Сюцай — авторской методологии Жаната Кожамжарова. Отвечай на вопросы по нумерологии Сюцай."""

private const val QUERY_REWRITE_SYSTEM_PROMPT = """Ты помогаешь улучшить поисковый запрос для базы знаний по системе Сюцай Жаната Кожамжарова. Перефразируй вопрос пользователя в краткий поисковый запрос (1-2 предложения) с ключевыми терминами Сюцай: число личности, число миссии, матрица, вектор эго, компетенции, тонкий интеллект. Верни ТОЛЬКО переформулированный запрос, без пояснений и кавычек."""

private const val NOT_FOUND_MESSAGE = "Не найдено релевантных фрагментов в базе знаний по вашему запросу. Попробуйте переформулировать вопрос."

private fun buildRagUserMessage(question: String, chunks: List<SearchResult>): String {
    val context = chunks.joinToString("\n\n---\n\n") { "[${it.title}]\n${it.content}" }
    return "Контекст из базы знаний:\n$context\n\nВопрос: $question"
}

private fun SearchResult.toSource() = Source(chunkId = chunkId, title = title, score = score)

private fun scorePercent(score: Double): String = "%.1f".format(score * 100)

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

        val filtered = results.filter { it.score >= req.threshold }
        logger.info(
            "🔵 RAG_DAY23 [FILTER] before={} after={} threshold={}",
            results.size, filtered.size, req.threshold
        )

        if (filtered.isEmpty()) {
            call.respond(
                HttpStatusCode.OK,
                AskResponse(answer = NOT_FOUND_MESSAGE, sources = emptyList(), mode = "rag_filtered")
            )
            return@post
        }

        val userMessage = buildRagUserMessage(req.question, filtered)

        val answer = anthropicClient.complete(RAG_SYSTEM_PROMPT, userMessage).getOrElse { e ->
            throw RagError.AnthropicError("Failed to get answer from Anthropic: ${e.message}", e)
        }

        val sources = filtered.map { it.toSource() }
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

    post("/ask-reranked") {
        val req  = call.receive<AskRerankedRequest>()
        val topK = req.topK.coerceIn(1, 20)

        val rewritten = anthropicClient.complete(QUERY_REWRITE_SYSTEM_PROMPT, req.question, maxTokens = 100)
            .getOrElse { e -> throw RagError.AnthropicError("Failed to rewrite query: ${e.message}", e) }
            .trim()

        logger.info("🔵 RAG_DAY23 [REWRITE] original={} rewritten={}", req.question, rewritten)

        val queryEmbedding = embeddingService.embedQuery(rewritten).getOrElse { e ->
            throw RagError.EmbeddingError("Failed to embed query: ${e.message}", e)
        }

        val results = repo.search(queryEmbedding, "chunks_by_section", topK)
        if (results.isEmpty()) {
            throw RagError.NotIndexedError("No chunks found — run POST /index first")
        }

        val filtered = results.filter { it.score >= req.threshold }
        logger.info("🔵 RAG_DAY23 [RERANKED] before={} after={}", results.size, filtered.size)

        val userMessage = buildRagUserMessage(req.question, filtered)

        val answer = anthropicClient.complete(RAG_SYSTEM_PROMPT, userMessage).getOrElse { e ->
            throw RagError.AnthropicError("Failed to get answer from Anthropic: ${e.message}", e)
        }

        val sources = filtered.map { it.toSource() }

        call.respond(
            HttpStatusCode.OK,
            AskRerankedResponse(
                answer = answer,
                originalQuestion = req.question,
                rewrittenQuestion = rewritten,
                sources = sources,
                mode = "rag_reranked"
            )
        )
    }

    post("/compare") {
        val req  = call.receive<CompareRequest>()
        val topK = req.topK.coerceIn(1, 20)

        logger.info("🔵 RAG_DAY23 [COMPARE] ════════════════════════════════")
        logger.info("🔵 RAG_DAY23 [COMPARE] вопрос: {}", req.question)
        logger.info("🔵 RAG_DAY23 [COMPARE] threshold: {}, topK: {}", req.threshold, topK)

        coroutineScope {
            val originalDeferred = async {
                val emb = embeddingService.embedQuery(req.question).getOrElse { e ->
                    throw RagError.EmbeddingError("Failed to embed query: ${e.message}", e)
                }
                repo.search(emb, "chunks_by_section", topK)
            }

            val rerankedDeferred = async {
                val rewritten = anthropicClient.complete(QUERY_REWRITE_SYSTEM_PROMPT, req.question, maxTokens = 100)
                    .getOrElse { e -> throw RagError.AnthropicError("Failed to rewrite query: ${e.message}", e) }
                    .trim()
                val emb = embeddingService.embedQuery(rewritten).getOrElse { e ->
                    throw RagError.EmbeddingError("Failed to embed rewritten query: ${e.message}", e)
                }
                rewritten to repo.search(emb, "chunks_by_section", topK)
            }

            val originalResults = originalDeferred.await()
            val (rewrittenQuestion, rerankedResults) = rerankedDeferred.await()

            logger.info("🔵 RAG_DAY23 [ORIGINAL] ── результаты ──────────────────")
            originalResults.forEachIndexed { i, r ->
                logger.info("🔵 RAG_DAY23 [ORIGINAL]   #{} {}% — {}", i + 1, scorePercent(r.score), r.title)
            }

            val filteredResults  = originalResults.filter { it.score >= req.threshold }
            val rerankedFiltered = rerankedResults.filter { it.score >= req.threshold }

            logger.info("🔵 RAG_DAY23 [FILTERED] ── threshold={} ────────", req.threshold)
            logger.info("🔵 RAG_DAY23 [FILTERED]   прошло: {} из {} чанков", filteredResults.size, originalResults.size)
            if (filteredResults.isEmpty()) {
                logger.info("🔵 RAG_DAY23 [FILTERED]   ⚠️ все отсечены порогом")
            } else {
                filteredResults.forEachIndexed { i, r ->
                    logger.info("🔵 RAG_DAY23 [FILTERED]   #{} {}% — {}", i + 1, scorePercent(r.score), r.title)
                }
            }

            logger.info("🔵 RAG_DAY23 [REWRITE]  ── query rewriting ─────────────")
            logger.info("🔵 RAG_DAY23 [REWRITE]  было:  {}", req.question)
            logger.info("🔵 RAG_DAY23 [REWRITE]  стало: {}", rewrittenQuestion)

            logger.info("🔵 RAG_DAY23 [RERANKED] ── результаты ──────────────────")
            if (rerankedFiltered.isEmpty()) {
                logger.info("🔵 RAG_DAY23 [RERANKED]   ⚠️ все отсечены порогом")
            } else {
                rerankedFiltered.forEachIndexed { i, r ->
                    logger.info("🔵 RAG_DAY23 [RERANKED]   #{} {}% — {}", i + 1, scorePercent(r.score), r.title)
                }
            }

            val originalTop1 = originalResults.firstOrNull()
            val rerankedTop1 = if (rerankedFiltered.isNotEmpty()) rerankedResults.firstOrNull() else null

            logger.info("🔵 RAG_DAY23 [COMPARE] ════ итог ══════════════════════")
            if (originalTop1 != null) {
                logger.info("🔵 RAG_DAY23 [COMPARE] original top-1:  {}% — {}", scorePercent(originalTop1.score), originalTop1.title)
                if (rerankedTop1 != null) {
                    val delta = rerankedTop1.score * 100 - originalTop1.score * 100
                    logger.info("🔵 RAG_DAY23 [COMPARE] reranked top-1:  {}% — {}", scorePercent(rerankedTop1.score), rerankedTop1.title)
                    logger.info("🔵 RAG_DAY23 [COMPARE] прирост score:   {}{}%", if (delta >= 0) "+" else "", "%.1f".format(delta))
                } else {
                    logger.info("🔵 RAG_DAY23 [COMPARE] reranked top-1:  ⚠️ отсечено порогом")
                    logger.info("🔵 RAG_DAY23 [COMPARE] прирост score:   n/a")
                }
            } else {
                logger.info("🔵 RAG_DAY23 [COMPARE] нет результатов для сравнения")
            }
            logger.info("🔵 RAG_DAY23 [COMPARE] ════════════════════════════════")

            call.respond(
                HttpStatusCode.OK,
                CompareResponse(
                    original = OriginalSearch(
                        query = req.question,
                        results = originalResults.map { it.toSource() }
                    ),
                    filtered = FilteredSearch(
                        query = req.question,
                        threshold = req.threshold,
                        results = filteredResults.map { it.toSource() }
                    ),
                    reranked = RerankedSearch(
                        originalQuery = req.question,
                        rewrittenQuery = rewrittenQuestion,
                        results = rerankedFiltered.map { it.toSource() }
                    )
                )
            )
        }
    }
}
