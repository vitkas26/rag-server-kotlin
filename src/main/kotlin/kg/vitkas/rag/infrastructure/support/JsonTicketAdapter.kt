package kg.vitkas.rag.infrastructure.support

import kg.vitkas.rag.domain.model.TicketContext
import kg.vitkas.rag.domain.port.TicketPort
import kg.vitkas.rag.model.RagError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class TicketDto(
    val ticketId: String,
    val userName: String,
    val issue: String,
    val extra: Map<String, String> = emptyMap()
)

private val json = Json { ignoreUnknownKeys = true }

class JsonTicketAdapter(private val path: String) : TicketPort {

    override suspend fun findTicket(ticketId: String): TicketContext? {
        val tickets = runCatching {
            json.decodeFromString<List<TicketDto>>(File(path).readText())
        }.getOrElse { e ->
            throw RagError.TicketError("Tickets loading failed: ${e.message}", e)
        }
        return tickets.find { it.ticketId == ticketId }
            ?.let { TicketContext(it.ticketId, it.userName, it.issue, it.extra) }
    }
}
