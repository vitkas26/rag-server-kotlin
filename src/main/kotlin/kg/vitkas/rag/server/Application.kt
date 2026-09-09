package kg.vitkas.rag.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.intercept
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import java.util.Base64
import kg.vitkas.rag.config.AppConfig
import kg.vitkas.rag.domain.port.AgenticLlmPort
import kg.vitkas.rag.domain.port.FileToolPort
import kg.vitkas.rag.domain.port.GitInfoPort
import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.domain.port.ProjectDocsPort
import kg.vitkas.rag.domain.usecase.AnswerHelpQueryUseCase
import kg.vitkas.rag.domain.usecase.FileAssistantUseCase
import kg.vitkas.rag.infrastructure.llm.AnthropicAgenticLlmAdapter
import kg.vitkas.rag.infrastructure.llm.AnthropicLlmAdapter
import kg.vitkas.rag.infrastructure.llm.OllamaLlmAdapter
import kg.vitkas.rag.infrastructure.mcp.FilesystemMcpAdapter
import kg.vitkas.rag.infrastructure.mcp.GitInfoMcpAdapter
import kg.vitkas.rag.infrastructure.mcp.buildMcpGitServer
import kg.vitkas.rag.domain.port.TicketPort
import kg.vitkas.rag.domain.usecase.AnswerSupportQueryUseCase
import kg.vitkas.rag.infrastructure.rag.CodeContextRagAdapter
import kg.vitkas.rag.infrastructure.rag.FaqRagAdapter
import kg.vitkas.rag.infrastructure.rag.ProjectDocsRagAdapter
import kg.vitkas.rag.infrastructure.support.JsonTicketAdapter
import kg.vitkas.rag.model.ErrorResponse
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.pipeline.AnthropicClient
import kg.vitkas.rag.pipeline.EmbeddingService
import kg.vitkas.rag.pipeline.IndexRepository
import kg.vitkas.rag.pipeline.OllamaGenerationClient
import kg.vitkas.rag.server.routes.askRoutes
import kg.vitkas.rag.server.routes.codeIndexRoutes
import kg.vitkas.rag.server.routes.debugRoutes
import kg.vitkas.rag.server.routes.docsIndexRoutes
import kg.vitkas.rag.server.routes.faqIndexRoutes
import kg.vitkas.rag.server.routes.fileAssistantRoutes
import kg.vitkas.rag.server.routes.helpRoutes
import kg.vitkas.rag.server.routes.indexRoutes
import kg.vitkas.rag.server.routes.reviewRoutes
import kg.vitkas.rag.server.routes.searchRoutes
import kg.vitkas.rag.server.routes.supportRoutes
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.server.Application")

// Ручная проверка Basic Auth вместо io.ktor:ktor-server-auth — тот плагин на 401 автоматически
// ставит заголовок WWW-Authenticate: Basic, и браузер перехватывает это СВОИМ нативным окном
// логин/пароль поверх нашей формы в chat.html; значения, введённые в форме, туда не долетают —
// пользователь вводит логин/пароль в чужой попап и ничего не происходит. Без этого заголовка
// браузер не считает ответ auth-челленджем и просто отдаёт 401 в fetch(), как обычную ошибку.
private fun isValidBasicAuth(header: String?, config: AppConfig): Boolean {
    if (header == null || !header.startsWith("Basic ")) return false
    val decoded = runCatching {
        String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()))
    }.getOrNull() ?: return false
    val sepIndex = decoded.indexOf(':')
    if (sepIndex < 0) return false
    val user = decoded.substring(0, sepIndex)
    val password = decoded.substring(sepIndex + 1)
    return user == config.auth.user && password == config.auth.password
}

// route("") НЕ создаёт изолированную дочернюю ноду в Ktor 3.0.3 — интерцепторы, повешенные
// "внутри" него, эмпирически утекали на /search и /chat/chat.html (проверено curl'ом).
// authenticate() у ktor-server-auth раньше давал правильный scoping через собственный
// RouteSelector с RouteSelectorEvaluation.Transparent (matches always, без потребления
// path-сегмента, но с ГЕНУИННО отдельной route-нодой) — воспроизводим тот же механизм напрямую.
private object AskRoutesSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent
}

fun Application.module() {
    val config = AppConfig.from(environment.config)

    val httpClient = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // HttpTimeout — протокольный таймаут поверх движка, соблюдается независимо от engine{}.
        // На VPS без GPU генерация 7B-модели на CPU может занимать 1-2+ минуты.
        install(HttpTimeout) {
            requestTimeoutMillis = 600_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 600_000
        }
        // CIO default requestTimeout=15000ms — движковый таймаут, отдельный от HttpTimeout выше.
        // Держим >= requestTimeoutMillis: младший из двух побеждает, иначе HttpTimeout=300s
        // бессмысленнен, если engine всё равно обрубит раньше.
        engine {
            requestTimeout = 600_000
        }
    }
    monitor.subscribe(ApplicationStopped) { httpClient.close() }

    install(ContentNegotiation) { json() }

    install(StatusPages) {
        exception<RagError.NotIndexedError> { call, e ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Not indexed"))
        }
        exception<RagError> { call, e ->
            logger.error("Unhandled RagError in {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
        }
        exception<Throwable> { call, e ->
            logger.error("Unhandled exception in {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Unknown error"))
        }
        // RateLimit-плагин сам отвечает 429 без тела по умолчанию — этот хендлер перехватывает
        // ответ по статусу (независимо от источника) и подставляет тело, которое просит задание.
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorResponse("rate limit exceeded"))
        }
    }

    install(CallLogging)

    val askRateLimiter = IpRateLimiter(limit = 10, windowMs = 60_000)

    val embeddingService = EmbeddingService(httpClient, config)
    val indexRepository  = IndexRepository(config)
    val anthropicClient  = AnthropicClient(httpClient, config)

    // ollama.url — полный путь до /api/embeddings; для /api/chat нужен базовый адрес сервера.
    val ollamaBaseUrl = config.ollama.url.removeSuffix("/api/embeddings")
    val ollamaGenerationClient = OllamaGenerationClient(httpClient, ollamaBaseUrl, config.ollama.generationModel)

    // MCP git-tools сервер монтируется в этом же Ktor-приложении/порту (не отдельный процесс) —
    // GitInfoMcpAdapter ниже ходит на него же по loopback как настоящий MCP-клиент.
    // TODO: add auth before VPS deploy
    mcpStreamableHttp(path = "/mcp/git") { buildMcpGitServer() }

    val gitInfoPort: GitInfoPort = GitInfoMcpAdapter(config.mcp.baseUrl)
    val projectDocsPort: ProjectDocsPort = ProjectDocsRagAdapter(embeddingService, indexRepository, config)
    val codeContextPort: ProjectDocsPort = CodeContextRagAdapter(embeddingService, indexRepository, config)
    val llmPort: LlmPort = AnthropicLlmAdapter(anthropicClient)
    // Day 32→33: Anthropic geo-blocked (403) на VPS в РФ — review-PR переведён на локальную Ollama.
    val reviewLlmPort: LlmPort = OllamaLlmAdapter(ollamaGenerationClient)
    val answerHelpQueryUseCase = AnswerHelpQueryUseCase(gitInfoPort, projectDocsPort, llmPort)
    val ticketPort: TicketPort = JsonTicketAdapter(config.tickets.path)
    val faqPort: ProjectDocsPort = FaqRagAdapter(embeddingService, indexRepository, config)
    val answerSupportQueryUseCase = AnswerSupportQueryUseCase(ticketPort, faqPort, llmPort)
    // Day 32: diff — прямой git через ProcessBuilder, БЕЗ MCP (репо и раннер CI на одной машине).
    // GitDiffAdapter не wire-ится здесь синглтоном — конструируется в ReviewRoutes под repoPath
    // конкретного запроса (см. комментарий там).
    val defaultRepoPath = System.getProperty("user.dir")
    monitor.subscribe(ApplicationStopped) { (gitInfoPort as GitInfoMcpAdapter).close() }

    // Day 34: файловый ассистент — MCP filesystem server как отдельный npm-процесс (stdio),
    // в отличие от gitInfoPort выше (HTTP MCP-сервер в этом же Ktor-процессе).
    val fileToolPort: FileToolPort = FilesystemMcpAdapter(config.filesystem.command, defaultRepoPath)
    val agenticLlmPort: AgenticLlmPort = AnthropicAgenticLlmAdapter(anthropicClient)
    val fileAssistantUseCase = FileAssistantUseCase(fileToolPort, agenticLlmPort)
    monitor.subscribe(ApplicationStopped) { (fileToolPort as FilesystemMcpAdapter).close() }

    routing {
        // Вне createChild(AskRoutesSelector) ниже — страница открывается без Basic Auth,
        // авторизация нужна только самим fetch()-запросам к /ask-* из формы, не самой странице.
        staticResources("/chat", "static")
        indexRoutes(embeddingService, indexRepository, config)
        docsIndexRoutes(embeddingService, indexRepository, config)
        faqIndexRoutes(embeddingService, indexRepository, config)
        codeIndexRoutes(embeddingService, indexRepository, config)
        searchRoutes(embeddingService, indexRepository, config)
        // Rate limit + Basic Auth (10 req/min per IP) — только на /ask-*, per задание.
        createChild(AskRoutesSelector).apply {
            intercept(ApplicationCallPipeline.Plugins) {
                val ip = call.request.origin.remoteHost
                if (!askRateLimiter.tryAcquire(ip)) {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("rate limit exceeded"))
                    finish()
                }
            }
            intercept(ApplicationCallPipeline.Plugins) {
                if (!isValidBasicAuth(call.request.headers[HttpHeaders.Authorization], config)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials"))
                    finish()
                }
            }
            askRoutes(embeddingService, indexRepository, anthropicClient, ollamaGenerationClient, config)
            helpRoutes(answerHelpQueryUseCase)
            supportRoutes(answerSupportQueryUseCase)
            reviewRoutes(projectDocsPort, codeContextPort, reviewLlmPort, defaultRepoPath)
            fileAssistantRoutes(fileAssistantUseCase)
        }
        debugRoutes(embeddingService, indexRepository, ollamaGenerationClient)
    }
}
