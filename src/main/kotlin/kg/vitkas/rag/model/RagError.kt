package kg.vitkas.rag.model

sealed class RagError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    class PdfError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class EmbeddingError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class DatabaseError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class NotIndexedError(message: String) : RagError(message)
    class AnthropicError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class OllamaGenerationError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class DocsIndexError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class GitError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class TicketError(message: String, cause: Throwable? = null) : RagError(message, cause)
    class FaqIndexError(message: String, cause: Throwable? = null) : RagError(message, cause)
}
