package kg.vitkas.rag.domain.port

interface LlmPort {
    suspend fun complete(system: String, userMessage: String): String
}
