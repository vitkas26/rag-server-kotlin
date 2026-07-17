package kg.vitkas.rag.domain.usecase

import kg.vitkas.rag.domain.model.CodeReview
import kg.vitkas.rag.domain.port.DiffPort
import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.domain.port.ProjectDocsPort
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.domain.usecase.ReviewPullRequestUseCase")

private const val MAX_DIFF_QUERY_CHARS = 4000

private const val BUGS_HEADER = "## Баги"
private const val ARCH_HEADER = "## Архитектурные проблемы"
private const val RECS_HEADER = "## Рекомендации"
private val SECTION_HEADERS = listOf(BUGS_HEADER, ARCH_HEADER, RECS_HEADER)

// Day 29-30 экспериментально подтвердили: строгие промпты с фиксированной структурой
// работают надёжнее мягких формулировок — тот же принцип держим и здесь.
private const val SYSTEM_PROMPT_PREFIX = """Ты строгий ревьюер кода проекта rag-day21. Проанализируй предоставленный git diff, опираясь только на контекст ниже (архитектура проекта и похожий существующий код). Верни ответ СТРОГО в трёх секциях с этими точными заголовками и ни с какими другими:

## Баги
## Архитектурные проблемы
## Рекомендации

Если в категории нечего сказать — напиши "не найдено", не выдумывай проблемы. Отвечай по-русски, кратко, по существу."""

class ReviewPullRequestUseCase(
    private val diffPort: DiffPort,
    private val projectDocsPort: ProjectDocsPort,
    private val codeContextPort: ProjectDocsPort,
    private val llmPort: LlmPort
) {
    suspend fun execute(base: String, head: String, repoPath: String): CodeReview {
        logger.debug("Reviewing PR base={} head={} repoPath={}", base, head, repoPath)

        val diff = diffPort.getDiff(base, head)
        val changedFiles = diffPort.changedFiles(base, head)

        val queryText = (changedFiles.joinToString(", ") + "\n" + diff.take(MAX_DIFF_QUERY_CHARS)).trim()

        val contextParts = mutableListOf<String>()
        val sources = mutableListOf<String>()

        val docsChunks = if (queryText.isNotBlank()) projectDocsPort.search(queryText) else emptyList()
        if (docsChunks.isNotEmpty()) {
            contextParts += docsChunks.joinToString("\n\n") { "[${it.source}]\n${it.content}" }
            sources += docsChunks.map { it.source }.distinct()
        }

        val codeChunks = if (queryText.isNotBlank()) codeContextPort.search(queryText) else emptyList()
        if (codeChunks.isNotEmpty()) {
            contextParts += codeChunks.joinToString("\n\n") { "[${it.source}]\n${it.content}" }
            sources += codeChunks.map { it.source }.distinct()
        }

        val system = (listOf(SYSTEM_PROMPT_PREFIX) + contextParts).joinToString("\n\n")
        val rawAnswer = llmPort.complete(system, diff)

        return parseReview(rawAnswer, sources.distinct())
    }

    private fun parseReview(raw: String, sources: List<String>): CodeReview = CodeReview(
        bugs = parseListSection(extractSection(raw, BUGS_HEADER)),
        architectureIssues = parseListSection(extractSection(raw, ARCH_HEADER)),
        recommendations = parseListSection(extractSection(raw, RECS_HEADER)),
        sources = sources
    )

    private fun extractSection(raw: String, header: String): String {
        val startIdx = raw.indexOf(header)
        if (startIdx < 0) return ""
        val contentStart = startIdx + header.length
        val nextIdx = SECTION_HEADERS.filter { it != header }
            .mapNotNull { h -> raw.indexOf(h, contentStart).takeIf { it >= 0 } }
            .minOrNull() ?: raw.length
        return raw.substring(contentStart, nextIdx)
    }

    private fun parseListSection(section: String): List<String> {
        val trimmed = section.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("не найдено", ignoreCase = true)) return emptyList()
        return trimmed.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotBlank() }
    }
}
