package kg.vitkas.rag.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    val strategy: String = "by_structure",
    val topK: Int = 5
)

@Serializable
data class SearchResult(
    val chunkId: String,
    val title: String,
    val section: String,
    val content: String,
    val score: Double,
    val strategy: String
)

@Serializable
data class SearchResponse(val results: List<SearchResult>, val count: Int)

@Serializable
data class IndexResponse(val fixedChunks: Int, val sectionChunks: Int, val durationMs: Long)

@Serializable
data class ErrorResponse(val error: String)
