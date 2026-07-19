package kg.vitkas.rag.domain.model

data class SupportAnswer(
    val answer: String,
    val sources: List<String>,
    val ticketFound: Boolean
)
