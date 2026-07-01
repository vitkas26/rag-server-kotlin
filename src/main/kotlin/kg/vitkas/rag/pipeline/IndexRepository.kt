package kg.vitkas.rag.pipeline

import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.model.Chunk
import kg.vitkas.rag.model.SearchResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import kotlin.math.sqrt

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.pipeline.IndexRepository")
private val json = Json { ignoreUnknownKeys = true }

class IndexRepository(private val config: AppConfig) {

    fun save(fixedChunks: List<Chunk>, sectionChunks: List<Chunk>): Result<Unit> = runCatching {
        logger.info("Saving {} fixed + {} section chunks to {}", fixedChunks.size, sectionChunks.size, config.rag.dbPath)
        DriverManager.getConnection("jdbc:sqlite:${config.rag.dbPath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chunks_fixed (
                        chunk_id TEXT PRIMARY KEY, source TEXT, strategy TEXT,
                        title TEXT, section TEXT, word_count INTEGER, content TEXT, embedding TEXT
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chunks_by_section (
                        chunk_id TEXT PRIMARY KEY, source TEXT, strategy TEXT,
                        title TEXT, section TEXT, word_count INTEGER, content TEXT, embedding TEXT
                    )
                """.trimIndent())
            }
            insertChunks(conn, fixedChunks, "chunks_fixed")
            insertChunks(conn, sectionChunks, "chunks_by_section")
        }
    }

    fun search(queryEmbedding: List<Double>, table: String, topK: Int): List<SearchResult> {
        logger.debug("Searching in {} (topK={})", table, topK)
        return loadAll(table)
            .filter { it.embedding.isNotEmpty() }
            .map { it to cosineSimilarity(queryEmbedding, it.embedding) }
            .sortedByDescending { (_, score) -> score }
            .take(topK)
            .map { (chunk, score) ->
                SearchResult(
                    chunkId  = chunk.chunkId,
                    title    = chunk.title,
                    section  = chunk.section,
                    content  = chunk.content,
                    score    = score,
                    strategy = chunk.strategy
                )
            }
    }

    fun stats(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        DriverManager.getConnection("jdbc:sqlite:${config.rag.dbPath}").use { conn ->
            for (table in listOf("chunks_fixed", "chunks_by_section")) {
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(
                        "SELECT COUNT(*) as cnt, AVG(word_count) as avg, MIN(word_count) as mn, MAX(word_count) as mx FROM $table"
                    )
                    result[table] = mapOf(
                        "count" to rs.getInt("cnt"),
                        "avg"   to rs.getDouble("avg"),
                        "min"   to rs.getInt("mn"),
                        "max"   to rs.getInt("mx")
                    )
                }
            }
        }
        return result
    }

    private fun loadAll(table: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        DriverManager.getConnection("jdbc:sqlite:${config.rag.dbPath}").use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT * FROM $table")
                while (rs.next()) {
                    chunks += Chunk(
                        chunkId   = rs.getString("chunk_id"),
                        source    = rs.getString("source"),
                        strategy  = rs.getString("strategy"),
                        title     = rs.getString("title"),
                        section   = rs.getString("section"),
                        wordCount = rs.getInt("word_count"),
                        content   = rs.getString("content"),
                        embedding = json.decodeFromString(rs.getString("embedding"))
                    )
                }
            }
        }
        return chunks
    }

    private fun insertChunks(conn: java.sql.Connection, chunks: List<Chunk>, table: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO $table VALUES (?,?,?,?,?,?,?,?)").use { ps ->
            chunks.forEach { chunk ->
                ps.setString(1, chunk.chunkId)
                ps.setString(2, chunk.source)
                ps.setString(3, chunk.strategy)
                ps.setString(4, chunk.title)
                ps.setString(5, chunk.section)
                ps.setInt(6, chunk.wordCount)
                ps.setString(7, chunk.content)
                ps.setString(8, Json.encodeToString(chunk.embedding))
                ps.executeUpdate()
            }
        }
        logger.debug("Saved {} chunks to {}", chunks.size, table)
    }

    private fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        val dot   = a.zip(b).sumOf { (x, y) -> x * y }
        val normA = sqrt(a.sumOf { it * it })
        val normB = sqrt(b.sumOf { it * it })
        return if (normA == 0.0 || normB == 0.0) 0.0 else dot / (normA * normB)
    }
}
