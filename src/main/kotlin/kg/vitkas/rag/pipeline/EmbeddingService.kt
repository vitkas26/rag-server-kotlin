package kg.vitkas.rag.pipeline

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.model.Chunk
import kg.vitkas.rag.model.OllamaRequest
import kg.vitkas.rag.model.OllamaResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.pipeline.EmbeddingService")

private const val EMBED_BATCH_SIZE = 20

class EmbeddingService(private val client: HttpClient, private val config: AppConfig) {

    suspend fun embedChunks(chunks: List<Chunk>): Result<List<Chunk>> = runCatching {
        logger.info("Embedding {} chunks (parallel, batch size {})", chunks.size, EMBED_BATCH_SIZE)
        chunks.chunked(EMBED_BATCH_SIZE).flatMapIndexed { batchIndex, batch ->
            coroutineScope {
                batch.mapIndexed { i, chunk ->
                    async {
                        val globalIndex = batchIndex * EMBED_BATCH_SIZE + i
                        logger.debug("Embedding chunk {}/{}: {}", globalIndex + 1, chunks.size, chunk.chunkId)
                        val response: OllamaResponse = client.post(config.ollama.url) {
                            contentType(ContentType.Application.Json)
                            setBody(OllamaRequest(model = config.ollama.model, prompt = chunk.content))
                        }.body()
                        chunk.copy(embedding = response.toVector())
                    }
                }.awaitAll()
            }
        }
    }

    suspend fun embedQuery(query: String): Result<List<Double>> = runCatching {
        logger.debug("Embedding query: {}", query.take(80))
        val response: OllamaResponse = client.post(config.ollama.url) {
            contentType(ContentType.Application.Json)
            setBody(OllamaRequest(model = config.ollama.model, prompt = query))
        }.body()
        response.toVector()
    }
}
