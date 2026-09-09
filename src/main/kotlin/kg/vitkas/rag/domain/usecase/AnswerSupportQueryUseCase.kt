package kg.vitkas.rag.domain.usecase

import kg.vitkas.rag.domain.model.SupportAnswer
import kg.vitkas.rag.domain.model.TicketContext
import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.domain.port.ProjectDocsPort
import kg.vitkas.rag.domain.port.TicketPort
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.domain.usecase.AnswerSupportQueryUseCase")

private const val SYSTEM_PROMPT_PREFIX =
    "Ты ассистент поддержки пользователей приложения SyutsaiMentorPro. Отвечай кратко и по-русски, " +
        "опираясь только на контекст ниже. Если контекста недостаточно для ответа — прямо скажи, что " +
        "недостаточно информации и нужна консультация специалиста. Никогда не выдумывай факты про методологию."

class AnswerSupportQueryUseCase(
    private val ticketPort: TicketPort,
    private val faqPort: ProjectDocsPort,
    private val llmPort: LlmPort
) {

    suspend fun execute(query: String, ticketId: String?): SupportAnswer {
        val ticket: TicketContext? = ticketId?.let { ticketPort.findTicket(it) }
        val ticketFound = ticket != null

        val contextParts = mutableListOf<String>()
        val sources = mutableListOf<String>()

        if (ticket != null) {
            contextParts += "Контекст тикета ${ticket.ticketId} (пользователь ${ticket.userName}): ${ticket.issue}"
        }

        val chunks = faqPort.search(query)
        logger.debug(
            "FAQ search returned {} chunks: {}",
            chunks.size,
            chunks.joinToString(", ") { "${it.source}(score=${"%.3f".format(it.score)})" }
        )
        if (chunks.isNotEmpty()) {
            contextParts += chunks.joinToString("\n\n") { "[${it.source}]\n${it.content}" }
            sources += chunks.map { it.source }.distinct()
        }

        logger.debug("Support query (ticketId={}, ticketFound={}): {}", ticketId, ticketFound, query.take(80))

        val system = (listOf(SYSTEM_PROMPT_PREFIX) + contextParts).joinToString("\n\n")
        logger.debug("Support system prompt: {}", system)
        val answer = llmPort.complete(system, query)

        return SupportAnswer(answer = answer, sources = sources, ticketFound = ticketFound)
    }
}
