# Архитектура rag-day21

## Пакеты

- `config/` — `AppConfig.kt`: data classes конфига (ollama/rag/anthropic/auth/docs/mcp),
  читается из `application.conf` (`AppConfig.from`) или из env-переменных в CLI-режиме
  (`AppConfig.fromDefaults`).
- `model/` — DTO, пересекающие HTTP-границу (`@Serializable`), плюс `Chunk`/`Section` и
  sealed-иерархия ошибок `RagError` (`PdfError`, `EmbeddingError`, `DatabaseError`,
  `NotIndexedError`, `AnthropicError`, `OllamaGenerationError`, `DocsIndexError`, `GitError`).
- `pipeline/` — бизнес-логика без HTTP: chunking (`Chunker.kt`, `IndexingSource.kt`/
  `DocsIndexingSource`), эмбеддинги (`EmbeddingService.kt`, Ollama), хранилище
  (`IndexRepository.kt`, SQLite, brute-force cosine similarity), клиенты генерации
  (`AnthropicClient.kt`, `OllamaGenerationClient.kt`), извлечение текста (`PdfExtractor.kt` —
  на деле markdown).
- `server/routes/` — Ktor-роуты, один файл на группу (`IndexRoutes`, `DocsIndexRoutes`,
  `SearchRoutes`, `AskRoutes`, `HelpRoutes`, `DebugRoutes`).
- `server/Application.kt` — единственная точка сборки приложения: DI, `StatusPages`,
  Basic Auth, rate limit, монтирование роутов и MCP-сервера.

## Day 31: Clean Architecture слой для dev-ассистента

- `domain/model/DocChunk.kt` — доменная модель результата поиска по докам
  (`content`, `source`, `score`), без привязки к SQLite/Chunk из `model/`.
- `domain/port/` — интерфейсы без деталей инфраструктуры: `GitInfoPort` (`currentBranch()`),
  `ProjectDocsPort` (`search(query): List<DocChunk>`), `LlmPort` (`complete(system, user)`).
- `domain/usecase/AnswerHelpQueryUseCase.kt` — принимает три порта через конструктор,
  keyword-эвристикой решает, нужен ли git-контекст и/или docs-контекст, собирает
  system-prompt, зовёт `LlmPort`, возвращает ответ + список источников
  (`git:branch=<name>` и/или пути файлов).

## Day 33: Clean Architecture слой для support-ассистента

- `domain/model/` — `SupportAnswer` (результат usecase: `answer`, `sources`, `ticketFound`),
  `TicketContext` (доменная модель тикета: `ticketId`, `userName`, `issue`, `extra`).
- `domain/port/TicketPort.kt` — интерфейс без знания про источник тикетов:
  `findTicket(ticketId): TicketContext?`.
- `domain/usecase/AnswerSupportQueryUseCase.kt` — принимает три порта через конструктор
  (`TicketPort`, `ProjectDocsPort` из Day 31, `LlmPort`). Опционально получает `ticketId`,
  ищет тикет через `TicketPort`, ищет FAQ в docs-коллекции через `ProjectDocsPort`,
  собирает system-prompt (префикс про роль support-ассистента + контекст тикета +
  найденные chunks), зовёт `LlmPort`, возвращает `SupportAnswer`.
- `infrastructure/support/JsonTicketAdapter.kt` — implements `TicketPort`. Читает тикеты
  из JSON-файла (путь в конструкторе), десериализует, ищет по `ticketId`.
  При ошибке загрузки бросает `RagError.TicketError`.
- `POST /support` (`SupportRoutes.kt`) — в защищённом Basic Auth блоке, рядом с `/help`.

## Инфраструктура (адаптеры портов)

- `infrastructure/mcp/GitInfoMcpAdapter.kt` — implements `GitInfoPort`. Настоящий MCP-клиент
  (`kotlin-sdk-client`, `StreamableHttpClientTransport`) ходит на MCP-сервер, смонтированный
  в этом же Ktor-приложении на `/mcp/git` (`McpGitServerFactory.kt` + `GitBranchToolHandler.kt`,
  тул `git_current_branch` через `ProcessBuilder("git","rev-parse","--abbrev-ref","HEAD")`).
- `infrastructure/rag/ProjectDocsRagAdapter.kt` — implements `ProjectDocsPort`. Переиспользует
  существующие `EmbeddingService`/`IndexRepository`, ищет в отдельной SQLite-таблице
  `docs_chunks` (константа `DOCS_TABLE`), не пересекается с `chunks_fixed`/`chunks_by_section`
  базы знаний.
- `infrastructure/llm/AnthropicLlmAdapter.kt` — implements `LlmPort`, тонкая обёртка над
  существующим `pipeline/AnthropicClient.kt` (Haiku, `application.conf#anthropic`).
- `infrastructure/support/JsonTicketAdapter.kt` — implements `TicketPort`. Читает JSON-файл
  по пути из конструктора (`kotlinx.serialization`), десериализует в `List<TicketDto>`,
  ищет по `ticketId`. При ошибке чтения/парсинга бросает `RagError.TicketError`.

## DI

Koin не используется — весь wiring ручной, внутри `fun Application.module()`
(`server/Application.kt`). Порты конструируются как `val gitInfoPort: GitInfoPort =
GitInfoMcpAdapter(...)` и передаются в `AnswerHelpQueryUseCase` по конструктору;
`GitInfoMcpAdapter.close()` вызывается на `ApplicationStopped`.

## Day 34: файловый ассистент через MCP filesystem server

- `domain/port/FileToolPort.kt` — интерфейс без знания про MCP: `listDirectory(path)`,
  `readFile(path)`, `writeFile(path, content)`, `searchFiles(pattern)`.
- `domain/port/AgenticLlmPort.kt` — `sendTurn(system, history, tools): AgentTurn`,
  провайдер-независимый агентный контур (не завязан на Anthropic wire-формат), отдельно
  от простого `LlmPort` (Day 31), который tools API не поддерживает.
- `domain/model/` — `AgentMessage`/`AgentContentBlock` (Text/ToolUse/ToolResult, история
  диалога с LLM), `ToolDefinition`/`ToolParam` (описание тула для LLM), `AgentTurn`
  (`ToolCallRequested`/`FinalAnswer`), `AgentToolCall`/`FileAssistantAnswer` (результат
  usecase).
- `domain/usecase/FileAssistantUseCase.kt` — agent loop до 10 итераций: `sendTurn` →
  `ToolCallRequested` вызывает соответствующие методы `FileToolPort`, ошибка выполнения
  тула превращается в `ToolResult(isError=true)` (не бросает — даёт LLM шанс поправиться),
  `FinalAnswer` завершает цикл. Чистый usecase без HTTP-зависимостей — задел под будущий
  `OrchestratorUseCase` (Day 35), который сможет вызывать его напрямую как субагента.
- `infrastructure/mcp/FilesystemMcpAdapter.kt` — implements `FileToolPort`. В отличие от
  `GitInfoMcpAdapter` (Day 31, HTTP MCP-сервер в этом же Ktor-процессе), здесь MCP-сервер —
  ОТДЕЛЬНЫЙ npm-процесс (`npx @modelcontextprotocol/server-filesystem <repoPath>`),
  общающийся по stdio через `StdioClientTransport` (`kotlin-sdk-client`). Ограничение
  доступа к каталогу проекта обеспечивает сам официальный сервер (единственный разрешённый
  путь передаётся аргументом при старте) — собственной защиты от выхода за пределы
  репозитория в коде нет.
- `infrastructure/llm/AnthropicAgenticLlmAdapter.kt` — implements `AgenticLlmPort`.
  Транслирует domain-модели агентного цикла в Anthropic tools API wire-формат
  (`model/AnthropicAgentModels.kt`) и обратно, зовёт новый метод `completeWithTools`
  в существующем `pipeline/AnthropicClient.kt` (старый `complete()` не тронут).
- `POST /file-assistant` (`FileAssistantRoutes.kt`) — в защищённом Basic Auth блоке,
  рядом с `/help`/`/support`/`/review-pr`.
- Wiring в `Application.kt`: `FilesystemMcpAdapter` конструируется с командой из
  `application.conf#filesystem.command` (override `FILESYSTEM_MCP_COMMAND`) и тем же
  `defaultRepoPath`, что и `review-pr`; закрывается (`process.destroy()`) на
  `ApplicationStopped`.

## Индексация docs-коллекции

`POST /index-docs` (`DocsIndexRoutes.kt`) — отдельная от `POST /index` (база знаний).
Источники — `config.docs.paths` (см. `application.conf`, override через `DOCS_PATHS`),
читаются через `DocsIndexingSource`, чанкуются `chunkByFixedSize` с явным `source`
(путь файла), сохраняются в `docs_chunks` через `IndexRepository.saveToTable`.
