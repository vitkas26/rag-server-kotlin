package kg.vitkas.rag.domain.port

interface GitInfoPort {
    suspend fun currentBranch(): String
}
