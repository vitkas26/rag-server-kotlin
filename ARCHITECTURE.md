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

## DI

Koin не используется — весь wiring ручной, внутри `fun Application.module()`
(`server/Application.kt`). Порты конструируются как `val gitInfoPort: GitInfoPort =
GitInfoMcpAdapter(...)` и передаются в `AnswerHelpQueryUseCase` по конструктору;
`GitInfoMcpAdapter.close()` вызывается на `ApplicationStopped`.

## Индексация docs-коллекции

`POST /index-docs` (`DocsIndexRoutes.kt`) — отдельная от `POST /index` (база знаний).
Источники — `config.docs.paths` (см. `application.conf`, override через `DOCS_PATHS`),
читаются через `DocsIndexingSource`, чанкуются `chunkByFixedSize` с явным `source`
(путь файла), сохраняются в `docs_chunks` через `IndexRepository.saveToTable`.
