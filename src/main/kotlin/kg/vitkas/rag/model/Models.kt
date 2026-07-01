package kg.vitkas.rag.model

import kotlinx.serialization.Serializable

data class Section(
    val number: Int,
    val title: String,
    val content: String
)

data class Chunk(
    val chunkId: String,
    val source: String,
    val strategy: String,
    val title: String,
    val section: String,
    val wordCount: Int,
    val content: String,
    val embedding: List<Double> = emptyList()
)

@Serializable
data class OllamaRequest(val model: String, val prompt: String)

@Serializable
data class OllamaResponse(
    val embedding: List<Double>? = null,   // старый /api/embeddings
    val embeddings: List<List<Double>>? = null  // новый /api/embed
) {
    fun toVector(): List<Double> =
        embedding ?: embeddings?.firstOrNull() ?: emptyList()
}
