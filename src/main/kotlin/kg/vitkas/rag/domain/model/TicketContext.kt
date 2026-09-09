package kg.vitkas.rag.domain.model

data class TicketContext(
    val ticketId: String,
    val userName: String,
    val issue: String,
    val extra: Map<String, String> = emptyMap()
)
