package kg.vitkas.rag.pipeline

class FaqIndexingSource(private val path: String) : IndexingSource {
    override val name = "faq"

    override suspend fun loadDocuments(): Result<List<SourceDocument>> = runCatching {
        listOf(SourceDocument(path, extractTextFromMarkdown(path).getOrThrow()))
    }
}
