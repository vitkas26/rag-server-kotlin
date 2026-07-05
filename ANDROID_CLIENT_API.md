# RAG Server API — справка для Android-клиента

Справка для интеграции Android-приложения (например, SyutsaiMentorPro) с этим Ktor RAG-сервером.
Сервер — отдельный Kotlin/JVM процесс, не часть Android-проекта. Запускается локально: `./gradlew run`.

## Base URL

- С компа/postman: `http://localhost:8080`
- С Android-эмулятора (хост = comp): `http://10.0.2.2:8080`
- С физического Android-устройства: `http://<IP компа в локальной сети>:8080` (нужна одна Wi-Fi сеть)

Порт переопределяется env `PORT`. Авторизации нет (no auth, no API key на входе сервера).

## Формат ошибок (все эндпоинты)

```json
{ "error": "текст ошибки" }
```

| HTTP статус | Когда |
|---|---|
| 409 Conflict | `NotIndexedError` — индекс пустой, нужно сначала `POST /index` |
| 500 Internal Server Error | Любая другая ошибка (Ollama недоступна, Anthropic недоступен, парсинг PDF/MD и т.д.) |

## Эндпоинты

### `POST /index`
Индексирует markdown базу знаний (chunking + эмбеддинги + сохранение в SQLite). Вызывается один раз при первом деплое/обновлении базы знаний, не из мобильного клиента в рантайме.

Response 200:
```json
{ "fixedChunks": 42, "sectionChunks": 23, "durationMs": 15321 }
```

### `POST /search`
Сырой векторный поиск без LLM-ответа.

Request:
```json
{ "query": "число личности 7", "strategy": "by_structure", "topK": 5 }
```
`strategy`: `"by_structure"` (default) | `"fixed_size"`.

Response 200:
```json
{
  "results": [
    { "chunkId": "section_11_0", "title": "11. Число личности 7 — интерпретация по Жанату",
      "section": "Часть 11", "content": "...", "score": 0.85, "strategy": "by_structure" }
  ],
  "count": 5
}
```

### `POST /ask` — рекомендуется для клиента
Полный RAG с обязательными цитатами и источниками (Day 24). Простой векторный поиск (без query rewriting).

Request:
```json
{ "question": "что означает число личности 7", "topK": 3, "threshold": 0.55 }
```
(`topK` default 3, `threshold` default 0.0 — на проде ставить ~0.55)

Response 200 (успех):
```json
{
  "answer": "Число личности 7 — это вектор эго, направленный на познание и уединение...",
  "citations": [
    { "text": "Вектор эго. Направлен на познание и уединение...", "source": "Число личности 7" }
  ],
  "sources": [
    { "chunkId": "section_11_0", "title": "11. Число личности 7 — интерпретация по Жанату",
      "section": "Часть 11", "score": 0.8524 }
  ],
  "mode": "rag_with_citations"
}
```
Response 200 (не по теме / контекст не найден):
```json
{
  "answer": "НЕ ЗНАЮ: В базе знаний не найдено релевантной информации. Попробуйте переформулировать вопрос или снизить порог similarity.",
  "citations": [],
  "sources": [],
  "mode": "no_context"
}
```
`mode`: `"rag_with_citations"` | `"no_context"` — клиент должен ветвиться по этому полю (не по тексту answer).

### `POST /ask-day24` — то же самое + query rewriting
Идентичен `/ask` по форме ответа (те же `AskDay24Response`: answer/citations/sources/mode), но перед поиском LLM переформулирует вопрос (query rewriting) для лучшего recall — полезно для разговорных/неточных формулировок пользователя.

Request: `{ "question": "...", "topK": 5, "threshold": 0.55 }` (defaults).

Response — та же форма, что у `/ask` выше.

### `POST /ask-reranked` — legacy, старый формат (без цитат)
Оставлен для сравнения "было/стало", в проде не использовать. Ответ без секций цитат/источников как объекта — просто текст + `sources` (chunkId/title/score, без `section`).
```json
{ "answer": "...", "originalQuestion": "...", "rewrittenQuestion": "...", "sources": ["..."], "mode": "rag_reranked" }
```

### `POST /ask-no-rag`
Прямой вопрос к LLM без базы знаний (для A/B сравнения "с RAG / без RAG"). `{ "question": "..." }` → `{ "answer": "...", "mode": "no_rag" }`.

### `POST /compare`
Диагностический эндпоинт: параллельно гоняет original vs reranked поиск + threshold-фильтрацию, для отладки/видео-сравнения. Не для прод-клиента.

## Что использовать в Android-приложении

Для чата с ассистентом — **`/ask-day24`** (или `/ask`, если query rewriting не нужен): единственные эндпоинты с гарантированными цитатами/источниками и anti-hallucination guardrail'ом (`mode="no_context"` вместо галлюцинации, плюс серверная проверка, что цитаты реально дословные — см. `AskRoutes.kt` `verifyCitations`). Ветвление в UI строить по `mode`, а не парсить текст `answer`.
