package kg.vitkas.rag.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.intercept
import io.ktor.server.routing.routing
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.model.ErrorResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.AnthropicClient
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.OllamaGenerationClient
import kg.vitkas.rag.server.routes.askRoutes
import kg.vitkas.rag.server.routes.debugRoutes
import kg.vitkas.rag.server.routes.indexRoutes
import kg.vitkas.rag.server.routes.searchRoutes
import kotlinx.serialization.json.Json

fun Application.module() {
    val config = AppConfig.from(environment.config)

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // HttpTimeout — протокольный таймаут поверх движка, соблюдается независимо от engine{}.
        // На VPS без GPU генерация 7B-модели на CPU может занимать 1-2+ минуты.
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 300_000
        }
        // CIO default requestTimeout=15000ms — движковый таймаут, отдельный от HttpTimeout выше.
        // Держим >= requestTimeoutMillis: младший из двух побеждает, иначе HttpTimeout=300s
        // бессмысленнен, если engine всё равно обрубит раньше.
        engine {
            requestTimeout = 300_000
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
        // RateLimit-плагин сам отвечает 429 без тела по умолчанию — этот хендлер перехватывает
        // ответ по статусу (независимо от источника) и подставляет тело, которое просит задание.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorResponse("rate limit exceeded"))
        }
    }

    install(CallLogging)

    install(Authentication) {
        basic("rag-auth") {
            realm = "rag-day21"
            validate { credentials ->
                if (credentials.name == config.auth.user && credentials.password == config.auth.password) {
                    UserIdPrincipal(credentials.name)
                } else {
                    null
                }
            }
        }
    }

    // io.ktor:ktor-server-rate-limit регистрирует интерцептор ПОСЛЕ фазы Authentication (проверено
    // эмпирически) — неверные Basic Auth попытки не троттлились бы вообще. Ручной лимитер на фазе
    // Plugins гарантированно идёт перед auth. См. IpRateLimiter.kt.
    val askRateLimiter = IpRateLimiter(limit = 10, windowMs = 60_000)

    val embeddingService = EmbeddingService(httpClient, config)
    val indexRepository  = IndexRepository(config)
    val anthropicClient  = AnthropicClient(httpClient, config)

    // ollama.url — полный путь до /api/embeddings; для /api/chat нужен базовый адрес сервера.
    val ollamaBaseUrl = config.ollama.url.removeSuffix("/api/embeddings")
    val ollamaGenerationClient = OllamaGenerationClient(httpClient, ollamaBaseUrl, config.ollama.generationModel)

    routing {
        indexRoutes(embeddingService, indexRepository, config)
        searchRoutes(embeddingService, indexRepository, config)
        // Basic Auth + rate limit (10 req/min per IP) — только на /ask-*, per задание.
        // intercept() добавлен на той же route-ноде, что и authenticate(), но на более ранней
        // фазе (Plugins < AuthenticatePhase) — значит троттлит и неудачные попытки логина тоже,
        // не только успешные запросы (см. комментарий у askRateLimiter выше).
        authenticate("rag-auth") {
            intercept(ApplicationCallPipeline.Plugins) {
                val ip = call.request.origin.remoteHost
                if (!askRateLimiter.tryAcquire(ip)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limit exceeded"))
                    finish()
                }
            }
            askRoutes(embeddingService, indexRepository, anthropicClient, ollamaGenerationClient, config)
        }
        debugRoutes(embeddingService, indexRepository, ollamaGenerationClient)
    }
}
