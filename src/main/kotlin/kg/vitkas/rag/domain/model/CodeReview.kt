package kg.vitkas.rag.domain.model

data class CodeReview(
    val bugs: List<String>,
    val architectureIssues: List<String>,
    val recommendations: List<String>,
    val sources: List<String>
)
