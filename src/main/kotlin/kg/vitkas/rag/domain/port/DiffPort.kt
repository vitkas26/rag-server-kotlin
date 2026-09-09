package kg.vitkas.rag.domain.port

interface DiffPort {
    suspend fun getDiff(base: String, head: String): String
    suspend fun changedFiles(base: String, head: String): List<String>
}
