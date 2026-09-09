package kg.vitkas.rag.pipeline

data class SourceDocument(val path: String, val text: String)

interface IndexingSource {
    val name: String
    suspend fun loadDocuments(): Result<List<SourceDocument>>
}

class DocsIndexingSource(private val paths: List<String>) : IndexingSource {
    override val name = "project-docs"

    override suspend fun loadDocuments(): Result<List<SourceDocument>> = runCatching {
        paths.map { path -> SourceDocument(path, extractTextFromMarkdown(path).getOrThrow()) }
    }
}
