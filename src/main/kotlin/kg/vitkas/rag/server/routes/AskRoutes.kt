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
import kg.vitkas.rag.model.AskDay24Response
import kg.vitkas.rag.model.AskDay28Response
import kg.vitkas.rag.model.Citation
import kg.vitkas.rag.model.CloudAskResult
import kg.vitkas.rag.model.CompareLocalCloudResponse
import kg.vitkas.rag.model.Day24Source
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
import kg.vitkas.rag.pipeline.OllamaGenerationClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.server.routes.AskRoutes")

private const val RAG_SYSTEM_PROMPT = """Ты AI-ассистент ментора по системе Сюцай (методология Жаната Кожамжарова). Отвечай ТОЛЬКО на основе предоставленного контекста из базы знаний. Если ответа нет в контексте — скажи об этом явно. В конце ответа всегда указывай источники: названия разделов из которых взята информация."""

private const val NO_RAG_SYSTEM_PROMPT = """Ты AI-ассистент ментора по системе Сюцай — авторской методологии Жаната Кожамжарова. Отвечай на вопросы по нумерологии Сюцай."""

private const val QUERY_REWRITE_SYSTEM_PROMPT = """Ты помогаешь улучшить поисковый запрос для базы знаний по системе Сюцай Жаната Кожамжарова. Перефразируй вопрос пользователя в краткий поисковый запрос (1-2 предложения) с ключевыми терминами Сюцай: число личности, число миссии, матрица, вектор эго, компетенции, тонкий интеллект. Верни ТОЛЬКО переформулированный запрос, без пояснений и кавычек."""

private const val NO_CONTEXT_MESSAGE = "НЕ ЗНАЮ: В базе знаний не найдено релевантной информации. Попробуйте переформулировать вопрос или снизить порог similarity."

private const val DAY24_SYSTEM_PROMPT = """Ты AI-ассистент ментора по системе Сюцай (методология Жаната Кожамжарова). Отвечай СТРОГО на основе предоставленного контекста.

ОБЯЗАТЕЛЬНЫЙ ФОРМАТ ОТВЕТА (всегда, без исключений):

[ОТВЕТ]
{твой ответ на вопрос, 2-4 предложения}

[ЦИТАТЫ]
- "{дословная цитата из контекста, 1-2 предложения}" — {название раздела}
- "{ещё одна цитата}" — {название раздела}

[ИСТОЧНИКИ]
- {название раздела 1}
- {название раздела 2}

ПРАВИЛА:
1. Цитаты должны быть дословными фрагментами из контекста, не пересказом. Минимум 2 цитаты в каждом ответе.
2. Источники — только те разделы из которых взята информация.
3. Если в контексте нет ответа на вопрос — написать ТОЛЬКО: "НЕ ЗНАЮ: В базе знаний не найдено релевантной информации по данному вопросу. Пожалуйста, уточните вопрос или задайте его в контексте системы Сюцай." И ничего больше — никаких домыслов из общих знаний.
4. Никогда не добавляй информацию которой нет в контексте."""

private fun buildRagUserMessage(question: String, chunks: List<SearchResult>): String {
    val context = chunks.joinToString("\n\n---\n\n") { "[${it.title}]\n${it.content}" }
    return "Контекст из базы знаний:\n$context\n\nВопрос: $question"
}

private fun SearchResult.toSource() = Source(chunkId = chunkId, title = title, score = score)

private fun SearchResult.toDay24Source() = Day24Source(chunkId = chunkId, title = title, section = section, score = score)

private fun scorePercent(score: Double): String = "%.1f".format(score * 100)

private val SECTION_REGEX = Regex("""\[([А-ЯA-Z]+)\]\s*\n(.*?)(?=\n\[[А-ЯA-Z]+\]|\z)""", RegexOption.DOT_MATCHES_ALL)
private val CITATION_LINE_REGEX = Regex("""^-\s*"(.+?)"\s*—\s*(.+)$""")

private data class ParsedAnswer(val answer: String, val citations: List<Citation>) {
    val isDontKnow: Boolean get() = answer.startsWith("НЕ ЗНАЮ")
}

private fun parseDay24Answer(raw: String): ParsedAnswer {
    val trimmed = raw.trim()
    if (trimmed.startsWith("НЕ ЗНАЮ")) {
        return ParsedAnswer(answer = trimmed, citations = emptyList())
    }

    val sections = SECTION_REGEX.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2].trim() }

    val answer = sections["ОТВЕТ"] ?: trimmed
    val citations = sections["ЦИТАТЫ"]
        ?.lines()
        ?.mapNotNull { line -> CITATION_LINE_REGEX.find(line.trim())?.let { m -> Citation(text = m.groupValues[1], source = m.groupValues[2].trim()) } }
        ?: emptyList()

    return ParsedAnswer(answer, citations)
}

private const val MIN_CITATIONS = 2

private fun normalizeForMatch(s: String): String = s.replace(Regex("""\s+"""), " ").trim()

private fun verifyCitations(citations: List<Citation>, chunks: List<SearchResult>): List<Citation> {
    val normalizedContents = chunks.map { normalizeForMatch(it.content) }
    return citations.filter { citation ->
        val found = normalizedContents.any { it.contains(normalizeForMatch(citation.text)) }
        if (!found) {
            logger.warn("🔵 RAG_DAY24 [INVARIANT] цитата не найдена дословно в контексте, отброшена: \"{}\" — {}", citation.text, citation.source)
        }
        found
    }
}

private const val GENERATION_MAX_TOKENS = 1024

// Общий пайплайн day24 (rewrite → embed → search → filter → generate → parse), параметризован
// LLM-клиентом — используется /ask-local и /compare-local-cloud (день 28). /ask-day24 не трогаем.
private data class Day28PipelineResult(
    val parsed: ParsedAnswer,
    val filtered: List<SearchResult>,
    val rewrittenQuestion: String,
    val elapsedRewriteMs: Long,
    val elapsedGenerationMs: Long
)

private suspend fun runDay24Pipeline(
    question: String,
    topK: Int,
    threshold: Float,
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    complete: suspend (system: String, userMessage: String, maxTokens: Int) -> Result<String>,
    mapError: (String, Throwable) -> RagError
): Day28PipelineResult {
    val rewriteStart = System.currentTimeMillis()
    val rewritten = complete(QUERY_REWRITE_SYSTEM_PROMPT, question, 100)
        .getOrElse { e -> throw mapError("Failed to rewrite query: ${e.message}", e) }
        .trim()
    val elapsedRewriteMs = System.currentTimeMillis() - rewriteStart

    val queryEmbedding = embeddingService.embedQuery(rewritten).getOrElse { e ->
        throw RagError.EmbeddingError("Failed to embed query: ${e.message}", e)
    }

    val results = repo.search(queryEmbedding, "chunks_by_section", topK)
    if (results.isEmpty()) {
        throw RagError.NotIndexedError("No chunks found — run POST /index first")
    }

    val filtered = results.filter { it.score >= threshold }

    if (filtered.isEmpty()) {
        return Day28PipelineResult(
            parsed = ParsedAnswer(NO_CONTEXT_MESSAGE, emptyList()),
            filtered = filtered,
            rewrittenQuestion = rewritten,
            elapsedRewriteMs = elapsedRewriteMs,
            elapsedGenerationMs = 0L
        )
    }

    val userMessage = buildRagUserMessage(question, filtered)

    val generationStart = System.currentTimeMillis()
    val rawAnswer = complete(DAY24_SYSTEM_PROMPT, userMessage, GENERATION_MAX_TOKENS)
        .getOrElse { e -> throw mapError("Failed to get answer: ${e.message}", e) }
    val elapsedGenerationMs = System.currentTimeMillis() - generationStart

    return Day28PipelineResult(
        parsed = parseDay24Answer(rawAnswer),
        filtered = filtered,
        rewrittenQuestion = rewritten,
        elapsedRewriteMs = elapsedRewriteMs,
        elapsedGenerationMs = elapsedGenerationMs
    )
}

fun Route.askRoutes(
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    anthropicClient: AnthropicClient,
    ollamaGenerationClient: OllamaGenerationClient,
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
            logger.info(
                "🔵 RAG_DAY24 [NO_CONTEXT] вопрос отклонён — нет релевантных чанков, threshold={}",
                req.threshold
            )
            call.respond(
                HttpStatusCode.OK,
                AskResponse(answer = NO_CONTEXT_MESSAGE, citations = emptyList(), sources = emptyList(), mode = "no_context")
            )
            return@post
        }

        val userMessage = buildRagUserMessage(req.question, filtered)

        val rawAnswer = anthropicClient.complete(DAY24_SYSTEM_PROMPT, userMessage).getOrElse { e ->
            throw RagError.AnthropicError("Failed to get answer from Anthropic: ${e.message}", e)
        }

        val parsed = parseDay24Answer(rawAnswer)
        val mode = if (parsed.isDontKnow) "no_context" else "rag_with_citations"
        val citations = if (parsed.isDontKnow) emptyList() else verifyCitations(parsed.citations, filtered)
        val sources = if (parsed.isDontKnow) emptyList() else filtered.map { it.toDay24Source() }

        if (mode == "rag_with_citations" && citations.size < MIN_CITATIONS) {
            logger.warn("🔵 RAG_DAY24 [INVARIANT] мало цитат после проверки: {} (минимум {})", citations.size, MIN_CITATIONS)
        }

        logger.info(
            "🔵 RAG_DAY22 [RAG] question={} sources=[{}]",
            req.question,
            sources.joinToString(", ") { it.chunkId }
        )
        logger.info("🔵 RAG_DAY24 [CITATIONS] found={} цитат в ответе", citations.size)
        logger.info("🔵 RAG_DAY24 [ANSWER] mode={}", mode)

        call.respond(
            HttpStatusCode.OK,
            AskResponse(answer = parsed.answer, citations = citations, sources = sources, mode = mode)
        )
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

    post("/ask-day24") {
        val req  = call.receive<AskRerankedRequest>()
        val topK = req.topK.coerceIn(1, 20)

        val rewritten = anthropicClient.complete(QUERY_REWRITE_SYSTEM_PROMPT, req.question, maxTokens = 100)
            .getOrElse { e -> throw RagError.AnthropicError("Failed to rewrite query: ${e.message}", e) }
            .trim()

        logger.info("🔵 RAG_DAY24 [REWRITE] original={} rewritten={}", req.question, rewritten)

        val queryEmbedding = embeddingService.embedQuery(rewritten).getOrElse { e ->
            throw RagError.EmbeddingError("Failed to embed query: ${e.message}", e)
        }

        val results = repo.search(queryEmbedding, "chunks_by_section", topK)
        if (results.isEmpty()) {
            throw RagError.NotIndexedError("No chunks found — run POST /index first")
        }

        val filtered = results.filter { it.score >= req.threshold }
        logger.info("🔵 RAG_DAY24 [RERANKED] before={} after={}", results.size, filtered.size)

        if (filtered.isEmpty()) {
            logger.info(
                "🔵 RAG_DAY24 [NO_CONTEXT] вопрос отклонён — нет релевантных чанков, threshold={}",
                req.threshold
            )
            logger.info("🔵 RAG_DAY24 [ANSWER] mode={}", "no_context")
            call.respond(
                HttpStatusCode.OK,
                AskDay24Response(answer = NO_CONTEXT_MESSAGE, citations = emptyList(), sources = emptyList(), mode = "no_context")
            )
            return@post
        }

        val userMessage = buildRagUserMessage(req.question, filtered)

        val rawAnswer = anthropicClient.complete(DAY24_SYSTEM_PROMPT, userMessage).getOrElse { e ->
            throw RagError.AnthropicError("Failed to get answer from Anthropic: ${e.message}", e)
        }

        val parsed = parseDay24Answer(rawAnswer)
        val mode = if (parsed.isDontKnow) "no_context" else "rag_with_citations"
        val citations = if (parsed.isDontKnow) emptyList() else verifyCitations(parsed.citations, filtered)
        val sources = if (parsed.isDontKnow) emptyList() else filtered.map { it.toDay24Source() }

        if (mode == "rag_with_citations" && citations.size < MIN_CITATIONS) {
            logger.warn("🔵 RAG_DAY24 [INVARIANT] мало цитат после проверки: {} (минимум {})", citations.size, MIN_CITATIONS)
        }

        logger.info("🔵 RAG_DAY24 [CITATIONS] found={} цитат в ответе", citations.size)
        logger.info("🔵 RAG_DAY24 [ANSWER] mode={}", mode)

        call.respond(
            HttpStatusCode.OK,
            AskDay24Response(answer = parsed.answer, citations = citations, sources = sources, mode = mode)
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

    post("/ask-local") {
        val req  = call.receive<AskRerankedRequest>()
        val topK = req.topK.coerceIn(1, 20)

        val totalStart = System.currentTimeMillis()
        val result = runDay24Pipeline(
            question = req.question,
            topK = topK,
            threshold = req.threshold,
            embeddingService = embeddingService,
            repo = repo,
            complete = ollamaGenerationClient::complete,
            mapError = { msg, e -> RagError.OllamaGenerationError(msg, e) }
        )
        val elapsedTotalMs = System.currentTimeMillis() - totalStart

        val mode = if (result.parsed.isDontKnow) "no_context" else "rag_with_citations"
        val citations = if (result.parsed.isDontKnow) emptyList() else verifyCitations(result.parsed.citations, result.filtered)
        val sources = if (result.parsed.isDontKnow) emptyList() else result.filtered.map { it.toDay24Source() }

        logger.info(
            "🔵 RAG_DAY28 [ASK_LOCAL] question={} rewritten={} mode={} rewriteMs={} genMs={} totalMs={}",
            req.question, result.rewrittenQuestion, mode, result.elapsedRewriteMs, result.elapsedGenerationMs, elapsedTotalMs
        )

        call.respond(
            HttpStatusCode.OK,
            AskDay28Response(
                answer = result.parsed.answer,
                citations = citations,
                sources = sources,
                mode = mode,
                source = "local",
                elapsedRewriteMs = result.elapsedRewriteMs,
                elapsedGenerationMs = result.elapsedGenerationMs,
                elapsedTotalMs = elapsedTotalMs
            )
        )
    }

    post("/compare-local-cloud") {
        val req  = call.receive<AskRerankedRequest>()
        val topK = req.topK.coerceIn(1, 20)

        logger.info("🔵 RAG_DAY28 [COMPARE_LOCAL_CLOUD] question={}", req.question)

        coroutineScope {
            val cloudDeferred = async {
                val start = System.currentTimeMillis()
                val result = runDay24Pipeline(
                    question = req.question,
                    topK = topK,
                    threshold = req.threshold,
                    embeddingService = embeddingService,
                    repo = repo,
                    complete = anthropicClient::complete,
                    mapError = { msg, e -> RagError.AnthropicError(msg, e) }
                )
                result to (System.currentTimeMillis() - start)
            }

            val localDeferred = async {
                val start = System.currentTimeMillis()
                val result = runDay24Pipeline(
                    question = req.question,
                    topK = topK,
                    threshold = req.threshold,
                    embeddingService = embeddingService,
                    repo = repo,
                    complete = ollamaGenerationClient::complete,
                    mapError = { msg, e -> RagError.OllamaGenerationError(msg, e) }
                )
                result to (System.currentTimeMillis() - start)
            }

            val (cloudResult, cloudTotalMs) = cloudDeferred.await()
            val (localResult, localTotalMs) = localDeferred.await()

            val cloudMode = if (cloudResult.parsed.isDontKnow) "no_context" else "rag_with_citations"
            val cloudCitations = if (cloudResult.parsed.isDontKnow) emptyList() else verifyCitations(cloudResult.parsed.citations, cloudResult.filtered)
            val cloudSources = if (cloudResult.parsed.isDontKnow) emptyList() else cloudResult.filtered.map { it.toDay24Source() }

            val localMode = if (localResult.parsed.isDontKnow) "no_context" else "rag_with_citations"
            val localCitations = if (localResult.parsed.isDontKnow) emptyList() else verifyCitations(localResult.parsed.citations, localResult.filtered)
            val localSources = if (localResult.parsed.isDontKnow) emptyList() else localResult.filtered.map { it.toDay24Source() }

            logger.info(
                "🔵 RAG_DAY28 [COMPARE_LOCAL_CLOUD] cloud totalMs={} local totalMs={}",
                cloudTotalMs, localTotalMs
            )

            call.respond(
                HttpStatusCode.OK,
                CompareLocalCloudResponse(
                    cloud = CloudAskResult(
                        answer = cloudResult.parsed.answer,
                        citations = cloudCitations,
                        sources = cloudSources,
                        mode = cloudMode,
                        source = "cloud",
                        elapsedRewriteMs = cloudResult.elapsedRewriteMs,
                        elapsedGenerationMs = cloudResult.elapsedGenerationMs,
                        elapsedTotalMs = cloudTotalMs
                    ),
                    local = AskDay28Response(
                        answer = localResult.parsed.answer,
                        citations = localCitations,
                        sources = localSources,
                        mode = localMode,
                        source = "local",
                        elapsedRewriteMs = localResult.elapsedRewriteMs,
                        elapsedGenerationMs = localResult.elapsedGenerationMs,
                        elapsedTotalMs = localTotalMs
                    )
                )
            )
        }
    }
}
