package kg.vitkas.rag.domain.port

import kg.vitkas.rag.domain.model.TicketContext

interface TicketPort {
    suspend fun findTicket(ticketId: String): TicketContext?
}
