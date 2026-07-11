package kg.vitkas.rag.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.model.ErrorResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.AnthropicClient
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.OllamaGenerationClient
import kg.vitkas.rag.server.routes.askRoutes
import kg.vitkas.rag.server.routes.indexRoutes
import kg.vitkas.rag.server.routes.searchRoutes
import kotlinx.serialization.json.Json

fun Application.module() {
    val config = AppConfig.from(environment.config)

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // CIO default requestTimeout=15000ms — локальная генерация на 7B модели с RAG-контекстом
        // может занимать дольше, особенно на холодном старте модели в Ollama.
        engine {
            requestTimeout = 120_000
        }
    }
    monitor.subscribe(ApplicationStopped) { httpClient.close() }

    install(ContentNegotiation) { json() }

    install(StatusPages) {
        exception<RagError.NotIndexedError> { call, e ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Not indexed"))
        }
        exception<RagError> { call, e ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
        exception<Throwable> { call, e ->
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Unknown error"))
        }
    }

    install(CallLogging)

    val embeddingService = EmbeddingService(httpClient, config)
    val indexRepository  = IndexRepository(config)
    val anthropicClient  = AnthropicClient(httpClient, config)

    // ollama.url — полный путь до /api/embeddings; для /api/chat нужен базовый адрес сервера.
    val ollamaBaseUrl = config.ollama.url.removeSuffix("/api/embeddings")
    val ollamaGenerationClient = OllamaGenerationClient(httpClient, ollamaBaseUrl, config.ollama.generationModel)

    routing {
        indexRoutes(embeddingService, indexRepository, config)
        searchRoutes(embeddingService, indexRepository, config)
        askRoutes(embeddingService, indexRepository, anthropicClient, ollamaGenerationClient, config)
    }
}
