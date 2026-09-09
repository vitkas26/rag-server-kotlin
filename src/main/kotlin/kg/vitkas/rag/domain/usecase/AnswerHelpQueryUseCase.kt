package kg.vitkas.rag.domain.usecase

import kg.vitkas.rag.domain.port.GitInfoPort
import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.domain.port.ProjectDocsPort
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.domain.usecase.AnswerHelpQueryUseCase")

private val GIT_KEYWORDS = listOf("ветк", "branch", "git", "коммит", "commit")
private val DOCS_KEYWORDS = listOf(
    "архитектур", "структур", "пакет", "api", "route", "эндпоинт", "endpoint", "устроен", "модул"
)

private const val SYSTEM_PROMPT_PREFIX =
    "Ты ассистент разработчика проекта rag-day21. Отвечай кратко и по-русски, опираясь только на контекст ниже."

class AnswerHelpQueryUseCase(
    private val gitInfoPort: GitInfoPort,
    private val projectDocsPort: ProjectDocsPort,
    private val llmPort: LlmPort
) {
    data class Answer(val text: String, val sources: List<String>)

    suspend fun execute(query: String): Answer {
        val needsGit = GIT_KEYWORDS.any { query.contains(it, ignoreCase = true) }
        val needsDocs = DOCS_KEYWORDS.any { query.contains(it, ignoreCase = true) } || !needsGit

        val contextParts = mutableListOf<String>()
        val sources = mutableListOf<String>()

        if (needsGit) {
            val branch = gitInfoPort.currentBranch()
            contextParts += "Текущая git-ветка: $branch"
            sources += "git:branch=$branch"
        }

        if (needsDocs) {
            val chunks = projectDocsPort.search(query)
            if (chunks.isNotEmpty()) {
                contextParts += chunks.joinToString("\n\n") { "[${it.source}]\n${it.content}" }
                sources += chunks.map { it.source }.distinct()
            }
        }

        logger.debug("Routing query (needsGit={}, needsDocs={}): {}", needsGit, needsDocs, query.take(80))

        val system = (listOf(SYSTEM_PROMPT_PREFIX) + contextParts).joinToString("\n\n")
        val answer = llmPort.complete(system, query)

        return Answer(text = answer, sources = sources)
    }
}
