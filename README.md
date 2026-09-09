# RAG Day 21 — RAG-сервис + dev-ассистент

Kotlin/Ktor проект. CLI-индексация + HTTP-сервер, без Android.

## Что делает

- **Базовый RAG** (Day 21-29): индексирует `syucai_knowledge_base.md`, две стратегии chunking
  (`fixed_size` / `by_structure`), эмбеддинги через Ollama (`nomic-embed-text`), поиск —
  косинусная близость в SQLite. Генерация ответа — Anthropic (Haiku) или локальная Ollama-модель.
- **Dev-ассистент** (Day 31): отвечает на вопросы про сам проект — текущая git-ветка (через
  MCP-сервер, смонтированный в этом же приложении) и/или архитектура/структура (RAG по
  `README.md`/`ANDROID_CLIENT_API.md`/`ARCHITECTURE.md`, отдельная коллекция).
- **AI-ревью PR** (Day 32): по git diff между двумя ревизиями собирает контекст (архитектура +
  похожий существующий код) и просит LLM вернуть баги/архитектурные проблемы/рекомендации.
  Подключается как GitHub Action на `pull_request`.

Подробности архитектуры — в [ARCHITECTURE.md](ARCHITECTURE.md).
API для мобильного клиента — в [ANDROID_CLIENT_API.md](ANDROID_CLIENT_API.md).

## Требования

- Java 17+
- Ollama запущена: `ollama serve`, модель загружена: `ollama pull nomic-embed-text`
- `ANTHROPIC_API_KEY` — для `/ask*`, `/help`, `/support`, `/review-pr`, `/file-assistant` (генерация через Anthropic)
- `git` в PATH — для MCP git-tools и `/review-pr`
- Node.js/`npx` в PATH — для MCP filesystem-сервера (`/file-assistant`, `@modelcontextprotocol/server-filesystem`)

## Запуск

```bash
./gradlew run              # HTTP-сервер на :8080 (при первом запуске сам проиндексирует базу знаний)
./gradlew run --args="index"   # CLI-индексация без сервера
```

## Индексация (по требованию, без cron)

| Route | Источник | Таблица |
|---|---|---|
| `POST /index` | `syucai_knowledge_base.md` | `chunks_fixed`, `chunks_by_section` |
| `POST /index-docs` | `docs.paths` из `application.conf` (`DOCS_PATHS` env) | `docs_chunks` |
| `POST /index-code` | `.kt`-файлы из `src/main/kotlin` | `code_chunks` |
| `POST /index-faq` | `faq.md` (`FAQ_PATH` env) | `faq_chunks` |

## HTTP API

Без авторизации: `/index`, `/index-docs`, `/index-code`, `/index-faq`, `/search`, `/debug/context-size`, `/chat/*` (веб-форма).

С Basic Auth (`demo`/`demo123` по умолчанию, `RAG_AUTH_USER`/`RAG_AUTH_PASSWORD`) + rate limit
10 req/min/IP:

- `POST /ask`, `/ask-day24`, `/ask-no-rag`, `/ask-reranked`, `/ask-local`, `/ask-local-tuned`,
  `/ask-local-json`, `/compare`, `/compare-local-cloud`, `/day29-report` — RAG над базой знаний,
  разные экспериментальные варианты (см. [ANDROID_CLIENT_API.md](ANDROID_CLIENT_API.md))
- `POST /help` — `{"query": "..."}` → dev-ассистент (git-ветка и/или архитектура + LLM-ответ)
- `POST /support` — `{"query": "...", "ticketId": "опционально"}` →
  `{answer, sources, ticketFound}` — ассистент поддержки пользователей (тикеты + FAQ через RAG,
  не выдумывает факты при нехватке контекста)
- `POST /review-pr` — `{"base": "...", "head": "..."}` →
  `{bugs, architectureIssues, recommendations, sources}`
- `POST /file-assistant` — `{"query": "..."}` → `{answer, toolCalls: [{tool, args}]}` — агентный
  ReAct-цикл (до 10 итераций) с tool-use через MCP filesystem-сервер: чтение/запись/поиск файлов проекта

MCP: git-tools сервер смонтирован на `/mcp/git` (без auth, локальный, TODO перед VPS-деплоем).

## Конфигурация

Всё через `application.conf`, каждая настройка переопределяется env-переменной:
`OLLAMA_URL`, `OLLAMA_MODEL`, `OLLAMA_GENERATION_MODEL`, `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`,
`ANTHROPIC_MAX_TOKENS`, `RAG_AUTH_USER`, `RAG_AUTH_PASSWORD`, `DOCS_PATHS`, `FAQ_PATH`, `TICKETS_PATH`,
`MCP_BASE_URL`, `FILESYSTEM_MCP_COMMAND`, `PORT`.

## Testing

```bash
./gradlew test
```

18/18 unit-тестов, coverage-инструмент (Jacoco/Kover) не подключён.

## AI-ревью PR (GitHub Action)

`.github/workflows/ai-review.yml` — на каждый `pull_request` считает diff локально и шлёт его на
уже задеплоенный инстанс (`secrets.RAG_SERVICE_URL`, `secrets.RAG_BASIC_AUTH`), результат постит
комментарием к PR. Раннер CI ничего не поднимает сам (Ollama/эмбеддинги тяжелы для CI).
