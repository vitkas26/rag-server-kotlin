package kg.vitkas.rag.model

sealed class RagError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    class PdfError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class EmbeddingError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class DatabaseError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class NotIndexedError(message: String) : RagError(message)
}
