package kg.vitkas.rag.pipeline

import java.io.File

class CodeIndexingSource(
    private val rootDir: String = "src/main/kotlin",
    // explicit override — на будущее, чтобы индексировать только изменённые файлы PR,
    // а не сканировать весь rootDir заново.
    private val explicitPaths: List<String>? = null
) : IndexingSource {
    override val name = "project-code"

    override suspend fun loadDocuments(): Result<List<SourceDocument>> = runCatching {
        val paths = explicitPaths ?: scanKotlinFiles()
        paths.map { path -> SourceDocument(path, File(path).readText()) }
    }

    private fun scanKotlinFiles(): List<String> =
        File(rootDir).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.path }
            .sorted()
            .toList()
}
