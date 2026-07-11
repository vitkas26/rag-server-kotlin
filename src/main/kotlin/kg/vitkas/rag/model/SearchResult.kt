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

@Serializable
data class AskRequest(val question: String, val topK: Int = 3, val threshold: Float = 0.0f)

@Serializable
data class Source(val chunkId: String, val title: String, val score: Double)

@Serializable
data class Citation(val text: String, val source: String)

@Serializable
data class Day24Source(val chunkId: String, val title: String, val section: String, val score: Double)

@Serializable
data class AskResponse(
    val answer: String,
    val citations: List<Citation> = emptyList(),
    val sources: List<Day24Source>,
    val mode: String = "rag"
)

@Serializable
data class AskNoRagRequest(val question: String)

@Serializable
data class AskNoRagResponse(val answer: String, val mode: String = "no_rag")

@Serializable
data class AskRerankedRequest(val question: String, val topK: Int = 8, val threshold: Float = 0.55f)

@Serializable
data class AskRerankedResponse(
    val answer: String,
    val originalQuestion: String,
    val rewrittenQuestion: String,
    val sources: List<Source>,
    val mode: String = "rag_reranked"
)

@Serializable
data class CompareRequest(val question: String, val topK: Int = 5, val threshold: Float = 0.55f)

@Serializable
data class OriginalSearch(val query: String, val results: List<Source>)

@Serializable
data class FilteredSearch(val query: String, val threshold: Float, val results: List<Source>)

@Serializable
data class RerankedSearch(val originalQuery: String, val rewrittenQuery: String, val results: List<Source>)

@Serializable
data class CompareResponse(val original: OriginalSearch, val filtered: FilteredSearch, val reranked: RerankedSearch)

@Serializable
data class AskDay24Response(
    val answer: String,
    val citations: List<Citation>,
    val sources: List<Day24Source>,
    val mode: String
)

@Serializable
data class AskDay28Response(
    val answer: String,
    val citations: List<Citation>,
    val sources: List<Day24Source>,
    val mode: String,
    val source: String = "local",
    val elapsedRewriteMs: Long,
    val elapsedGenerationMs: Long,
    val elapsedTotalMs: Long
)

@Serializable
data class CloudAskResult(
    val answer: String,
    val citations: List<Citation>,
    val sources: List<Day24Source>,
    val mode: String,
    val source: String = "cloud",
    val elapsedRewriteMs: Long,
    val elapsedGenerationMs: Long,
    val elapsedTotalMs: Long
)

@Serializable
data class CompareLocalCloudResponse(val cloud: CloudAskResult, val local: AskDay28Response)
