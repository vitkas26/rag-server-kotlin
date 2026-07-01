package kg.vitkas.rag.pipeline

import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.pipeline.MarkdownExtractor")

fun extractTextFromMarkdown(path: String): Result<String> = runCatching {
    val file = File(path)
    require(file.exists()) { "Markdown file not found: $path" }
    logger.info("Reading markdown from {}", path)
    val text = file.readText()
    logger.debug("Read {} chars from {}", text.length, path)
    text
}
