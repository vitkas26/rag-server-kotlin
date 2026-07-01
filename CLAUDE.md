# CLAUDE.md — Правила кода для Kotlin/Ktor RAG проекта

## Управление ресурсами

- Каждый `Closeable` (`Connection`, `Statement`, `PDDocument`, `InputStream`) — только через `.use {}`. Никогда не закрывать вручную через `.close()` — это невидимо на путях с исключениями.
- `HttpClient` создаётся один раз, внедряется как зависимость, закрывается через `environment.monitor.subscribe(ApplicationStopped) { client.close() }` в Ktor модуле.
- `DriverManager.getConnection(...)` всегда в `.use {}`.

## Иммутабельность

- Все поля `data class` — только `val`. Никогда `var` в data class.
- Изменение состояния — через `.copy(field = newValue)`, не через мутацию.

## Обработка ошибок

- Функции, которые могут упасть, возвращают `Result<T>` (через `runCatching`) или бросают типизированный `RagError`.
- Никаких голых `throw Exception("message")` — всегда конкретный подкласс `RagError`.
- `StatusPages` в `Application.kt` — единственная точка маппинга ошибок в HTTP ответы.
- Никаких `println` для ошибок — только `logger.error("message", throwable)`.

## Корутины и параллелизм

- Параллельные HTTP запросы: `coroutineScope { list.map { async { ... } }.awaitAll() }`.
- Никаких `runBlocking` внутри `suspend` функций — только на верхнем уровне в `fun main`.
- `coroutineScope` вместо `GlobalScope` — структурированный параллелизм.

## Логирование

- SLF4J логгер: `private val logger = LoggerFactory.getLogger("kg.vitkas.rag.ИмяКласса")`.
- Никаких `println` в продакшн коде — только `logger.info / debug / warn / error`.
- Конфигурация в `src/main/resources/logback.xml`.
- Уровень для проекта: DEBUG. Для Ktor сервера: INFO.

## Конфигурация

- Все настройки — в `src/main/resources/application.conf` (HOCON).
- Каждая настройка поддерживает переопределение через env-переменную (`${?VAR_NAME}`).
- Никаких захардкоженных URL, путей, названий моделей в коде.
- `AppConfig.from(environment.config)` — единственная точка чтения конфига в server mode.
- `AppConfig.fromDefaults()` — для CLI mode (читает env-переменные, использует дефолты).

## Ktor Server

- Один Kotlin файл на группу роутов (`IndexRoutes.kt`, `SearchRoutes.kt`).
- Роуты — extension функции на `Route`, не top-level функции.
- Глобальная обработка ошибок только в `StatusPages` в `Application.kt`, не в роутах.
- `CallLogging` устанавливается для автоматического логирования запросов.
- `call.receive<T>()` и `call.respond(status, body)` — никогда не писать сырые байты ответа.

## Сериализация

- `@Serializable` на каждом классе, пересекающем HTTP границу (request/response bodies).
- `Json { ignoreUnknownKeys = true }` на HTTP клиенте — защита от изменений Ollama API.
- Эмбеддинги в SQLite хранятся как JSON TEXT; десериализация через `Json.decodeFromString<List<Double>>`.

## Структура пакетов

```
kg.vitkas.rag/
  config/    — конфигурационные data classes
  model/     — доменные модели и типы ошибок
  pipeline/  — бизнес-логика (без HTTP-сервера)
  server/    — Ktor модуль и роуты
  Main.kt    — точка входа, только диспетчеризация
```

## Запуск

```bash
# HTTP сервер (default)
./gradlew run

# CLI индексирование
./gradlew run --args="index"
```

## API

```
POST /index
  → запускает пайплайн индексирования (PDF → чанки → эмбеддинги → SQLite)
  → 200: { fixedChunks, sectionChunks, durationMs }
  → 500: { error: "..." }

POST /search
  body: { "query": "...", "strategy": "by_structure"|"fixed_size", "topK": 5 }
  → эмбеддит запрос, косинусный поиск по SQLite, возвращает top-K
  → 200: { results: [...], count: N }
  → 409: если индекс пустой (нужно сначала POST /index)
  → 500: если Ollama недоступна
```
