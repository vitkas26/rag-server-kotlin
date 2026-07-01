# RAG Day 21 — Индексация документов с эмбеддингами

Standalone Kotlin/JVM проект. Не Android — просто `fun main()`.

## Что делает

1. Читает `NURAi_technical_document.pdf`
2. Применяет две стратегии chunking:
   - **Стратегия A (fixed_size)**: режет текст на чанки по 500 слов с overlap 50 слов
   - **Стратегия B (by_structure)**: разбивает по 23 частям документа
3. Для каждого чанка получает эмбеддинг через Ollama (`nomic-embed-text`)
4. Сохраняет всё в `rag_index.db` (SQLite, две таблицы)
5. Выводит сравнительную статистику

## Требования

- Java 17+
- Ollama запущена: `ollama serve`
- Модель загружена: `ollama pull nomic-embed-text`
- Файл `NURAi_technical_document.pdf` лежит в корне проекта

## Запуск

В Android Studio: File → Open → выбрать папку `rag-day21` → Gradle sync → запустить `Main.kt`

Из терминала:
```bash
./gradlew run
```

## Ожидаемый вывод

```
🔵 RAG_DAY21 starting indexing pipeline
🔵 RAG_DAY21 extracting text from NURAi_technical_document.pdf
🔵 RAG_DAY21 extracted XXXXX chars
🔵 RAG_DAY21 strategy A (fixed_size): XX chunks
🔵 RAG_DAY21 found 23 sections
🔵 RAG_DAY21 strategy B (by_structure): XX chunks
🔵 RAG_DAY21 generating embeddings for strategy A...
🔵 RAG_DAY21 embedding chunk fixed_0 (1/XX)
...
=== Сравнение стратегий chunking ===

Стратегия A (fixed_size):
  Всего чанков:   XX
  Средний размер: 500 слов
  Мин: X слов, Макс: 500 слов

Стратегия B (by_structure):
  Всего чанков:   XX
  Средний размер: XXX слов
  Мин: XX слов, Макс: 800 слов

🔵 RAG_DAY21 indexing complete. DB: rag_index.db
```
