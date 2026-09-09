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

@Serializable
data class AskLocalTunedRequest(
    val question: String,
    val topK: Int = 8,   // retrieval topK (сколько чанков достать) — НЕ Ollama sampling top_k, см. ollamaTopK
    val threshold: Float = 0.55f,
    val temperature: Double? = null,
    val numPredict: Int? = null,
    val numCtx: Int? = null,
    val topP: Double? = null,
    val ollamaTopK: Int? = null,   // Ollama sampling top_k — отдельное имя, чтобы не путать с полем topK выше
    val repeatPenalty: Double? = null,
    val seed: Int? = null,
    // legacy — оставлено для обратной совместимости со старыми вызовами; если promptVariant
    // задан, он побеждает. Используется только когда promptVariant == null.
    val useOptimizedPrompt: Boolean = false,
    // "optimized" | "soft_quote" | null (null → useOptimizedPrompt решает, как раньше)
    val promptVariant: String? = null,
    val model: String? = null
)

@Serializable
data class ContextSizeResponse(
    val promptEvalCount: Int,
    val questionLength: Int,
    val chunksUsed: Int
)

// Один прогон одного сценария day29-report. Не Serializable — не пересекает HTTP-границу,
// только сводка (ExperimentSummary) попадает в ответ, сырые прогоны — в лог и в markdown-файл.
data class ExperimentRun(val citations: Int, val mode: String, val elapsedMs: Long)

@Serializable
data class ExperimentSummary(
    val label: String,
    // -1 → сценарий целиком упал по exception (например модель не найдена), метрика недействительна.
    // >=0 → реальный success rate (сколько прогонов из totalRuns дали citations>=2), даже если
    // часть прогонов внутри тоже падала — см. errorMessage.
    val successCount: Int,
    val totalRuns: Int,
    val avgMs: Long,
    // null → ни один прогон не падал по exception. Non-null → хотя бы один прогон упал —
    // текст объясняет причину, чтобы "0 успехов из-за отсутствующей модели" не выглядело
    // как "0 успехов потому что модель не справилась с задачей".
    val errorMessage: String? = null
)

@Serializable
data class Day29ReportRequest(
    val question: String = "Что такое число миссии?",
    val runsPerScenario: Int = 5
)

@Serializable
data class Day29ReportResponse(
    val reportPath: String,
    val summary: List<ExperimentSummary>
)

@Serializable
data class DocsIndexResponse(val chunks: Int, val durationMs: Long)

@Serializable
data class HelpRequest(val query: String)

@Serializable
data class HelpResponse(val answer: String, val sources: List<String>)

@Serializable
data class ReviewRequest(val base: String, val head: String)

@Serializable
data class ReviewResponse(
    val bugs: List<String>,
    val architectureIssues: List<String>,
    val recommendations: List<String>,
    val sources: List<String>
)

@Serializable
data class SupportRequest(val query: String, val ticketId: String? = null)

@Serializable
data class SupportResponse(val answer: String, val sources: List<String>, val ticketFound: Boolean)

@Serializable
data class FaqIndexResponse(val chunks: Int, val durationMs: Long)
