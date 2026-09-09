package kg.vitkas.rag.domain.usecase

import kg.vitkas.rag.domain.model.DocChunk
import kg.vitkas.rag.domain.model.TicketContext
import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.domain.port.ProjectDocsPort
import kg.vitkas.rag.domain.port.TicketPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class FakeTicketPort(private val tickets: Map<String, TicketContext> = emptyMap()) : TicketPort {
    var callCount = 0
        private set

    override suspend fun findTicket(ticketId: String): TicketContext? {
        callCount++
        return tickets[ticketId]
    }
}

private class FakeFaqPort(
    private val chunks: List<DocChunk> = listOf(DocChunk(content = "Число Миссии не редуцируется", source = "faq.md", score = 0.9))
) : ProjectDocsPort {
    override suspend fun search(query: String): List<DocChunk> = chunks
}

private class FakeSupportLlmPort : LlmPort {
    var lastSystem: String? = null

    override suspend fun complete(system: String, userMessage: String): String {
        lastSystem = system
        return "fake answer"
    }
}

class AnswerSupportQueryUseCaseTest {

    @Test
    fun `existing ticketId is found and reflected in the prompt`() = runTest {
        val ticket = TicketContext("T-101", "Айгуль", "Число Миссии = 11, почему не редуцировалось?")
        val ticketPort = FakeTicketPort(mapOf("T-101" to ticket))
        val faqPort = FakeFaqPort()
        val llmPort = FakeSupportLlmPort()
        val useCase = AnswerSupportQueryUseCase(ticketPort, faqPort, llmPort)

        val answer = useCase.execute("почему число миссии не редуцировалось?", "T-101")

        assertTrue(answer.ticketFound)
        assertEquals(1, ticketPort.callCount)
        assertTrue(llmPort.lastSystem!!.contains(ticket.issue))
    }

    @Test
    fun `unknown ticketId does not throw and reports ticketFound false`() = runTest {
        val ticketPort = FakeTicketPort()
        val faqPort = FakeFaqPort()
        val llmPort = FakeSupportLlmPort()
        val useCase = AnswerSupportQueryUseCase(ticketPort, faqPort, llmPort)

        val answer = useCase.execute("вопрос", "T-999")

        assertFalse(answer.ticketFound)
        assertEquals(1, ticketPort.callCount)
    }

    @Test
    fun `no ticketId never calls TicketPort`() = runTest {
        val ticketPort = FakeTicketPort()
        val faqPort = FakeFaqPort()
        val llmPort = FakeSupportLlmPort()
        val useCase = AnswerSupportQueryUseCase(ticketPort, faqPort, llmPort)

        val answer = useCase.execute("вопрос", null)

        assertFalse(answer.ticketFound)
        assertEquals(0, ticketPort.callCount)
    }
}
