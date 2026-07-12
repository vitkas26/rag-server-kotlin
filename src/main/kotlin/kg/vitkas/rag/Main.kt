package kg.vitkas.rag

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.EngineMain
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.chunkByFixedSize
import kg.vitkas.rag.pipeline.chunkBySection
import kg.vitkas.rag.pipeline.extractSections
import kg.vitkas.rag.pipeline.extractTextFromMarkdown
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.Main")

// Ниже этого размера считаем rag_index.db пустым/битым файлом-заглушкой, не настоящим индексом.
private const val MIN_VALID_DB_SIZE_BYTES = 1000L

fun main(args: Array<String>) {
    val config = AppConfig.fromDefaults()
    val forceReindex = System.getenv("FORCE_REINDEX")?.equals("true", ignoreCase = true) == true ||
        args.contains("--force-reindex")

    val dbFile = File(config.rag.dbPath)
    val hasExistingIndex = dbFile.exists() && dbFile.length() > MIN_VALID_DB_SIZE_BYTES

    if (hasExistingIndex && !forceReindex) {
        logger.info("🔵 Existing rag_index.db found, skipping reindex")
    } else {
        runBlocking { runCliIndexing() }
        logger.info("🔵 RAG_DAY21 indexing done, starting server on :8080...")
    }
    // --force-reindex не относится к EngineMain (парсит -port=, -P:key=value и т.п.) — не передаём дальше.
    EngineMain.main(args.filterNot { it == "--force-reindex" }.toTypedArray())
}

private suspend fun runCliIndexing() {
    val config = AppConfig.fromDefaults()
    logger.info("Starting CLI indexing pipeline")

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        // HttpTimeout — протокольный таймаут поверх движка, соблюдается независимо от engine{}.
        // На VPS без GPU генерация эмбеддингов на CPU может занимать дольше дефолтных 15s.
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 300_000
        }
        // CIO default requestTimeout=15000ms — движковый таймаут, отдельный от HttpTimeout выше.
        // Держим равным Application.kt (тот же fix для того же сценария), иначе младший из
        // двух победит и HttpTimeout=300s окажется бессмысленным.
        engine {
            requestTimeout = 300_000
        }
    }

    try {
        val text = extractTextFromMarkdown(config.rag.mdPath).getOrElse { e ->
            logger.error("Markdown extraction failed", e)
            return
        }

        val fixedChunks   = chunkByFixedSize(text, config.rag)
        val sections      = extractSections(text)
        val sectionChunks = chunkBySection(sections, config.rag)

        val embeddingService = EmbeddingService(httpClient, config)

        logger.info("Generating embeddings for strategy A ({} chunks)...", fixedChunks.size)
        val embeddedFixed = embeddingService.embedChunks(fixedChunks).getOrElse { e ->
            logger.error("Embedding failed (fixed)", e)
            return
        }

        logger.info("Generating embeddings for strategy B ({} chunks)...", sectionChunks.size)
        val embeddedSection = embeddingService.embedChunks(sectionChunks).getOrElse { e ->
            logger.error("Embedding failed (section)", e)
            return
        }

        val repo = IndexRepository(config)
        repo.save(embeddedFixed, embeddedSection).getOrElse { e ->
            logger.error("DB save failed", e)
            return
        }

        printComparison(repo)
        logger.info("Indexing complete. DB: {}", config.rag.dbPath)
    } finally {
        httpClient.close()
    }
}

private fun printComparison(repo: IndexRepository) {
    val stats = repo.stats()
    println()
    println("=== Сравнение стратегий chunking ===")
    println()

    @Suppress("UNCHECKED_CAST")
    fun printStats(label: String, key: String) {
        val s = stats[key] as? Map<String, Any> ?: return
        println("$label:")
        println("  Всего чанков:   ${s["count"]}")
        println("  Средний размер: ${"%.0f".format(s["avg"] as Double)} слов")
        println("  Мин: ${s["min"]} слов, Макс: ${s["max"]} слов")
        println()
    }

    printStats("Стратегия A (fixed_size)", "chunks_fixed")
    printStats("Стратегия B (by_structure)", "chunks_by_section")
}
