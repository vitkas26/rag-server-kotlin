package kg.vitkas.rag.domain.usecase

import kg.vitkas.rag.domain.model.AgentContentBlock
import kg.vitkas.rag.domain.model.AgentMessage
import kg.vitkas.rag.domain.model.AgentToolCall
import kg.vitkas.rag.domain.model.AgentTurn
import kg.vitkas.rag.domain.model.FileAssistantAnswer
import kg.vitkas.rag.domain.model.ToolDefinition
import kg.vitkas.rag.domain.model.ToolParam
import kg.vitkas.rag.domain.port.AgenticLlmPort
import kg.vitkas.rag.domain.port.FileToolPort
import kg.vitkas.rag.model.RagError
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.domain.usecase.FileAssistantUseCase")

private const val MAX_ITERATIONS = 10

// Экономия токенов в agent loop (by analogy с Day 25 task state summary): полная история
// пересылается заново на КАЖДОЙ итерации, значит цена read_file-результатов накапливается
// кумулятивно. Держим полным только САМЫЙ ПОСЛЕДНИЙ раунд tool_result — модель уже
// "обработала" более старые результаты, решая по ним следующий шаг; для дальнейших решений
// обычно достаточно короткой выжимки ("что было прочитано"), не всего текста файла.
private const val TOOL_RESULT_PREVIEW_CHARS = 200

// Если суммарный объём истории всё равно превышает разумный порог (например, тул вернул
// несколько больших файлов подряд) — сжимаем старые tool_result сильнее превентивно, не
// дожидаясь органического роста до предела контекста модели.
private const val MAX_HISTORY_CHARS = 50_000
private const val AGGRESSIVE_TOOL_RESULT_PREVIEW_CHARS = 60

private const val SYSTEM_PROMPT =
    "Ты ассистент, который анализирует и изменяет файлы Kotlin-проекта rag-day21 через MCP " +
        "filesystem tools: list_directory, read_file, write_file, search_files (поиск по " +
        "ИМЕНИ файла), search_in_file_contents (поиск ТЕКСТА внутри содержимого файлов).\n\n" +
        "Факты о структуре проекта (не угадывай другую структуру, не путай с другими " +
        "проектами): ВСЕ Kotlin-исходники лежат строго под src/main/kotlin/kg/vitkas/rag/, " +
        "других корневых пакетов нет. Внутри: domain/model/, domain/port/, domain/usecase/, " +
        "infrastructure/<слой>/ (mcp, llm, rag, git, support), model/ (DTO), pipeline/, " +
        "server/routes/, server/Application.kt. В domain/*, infrastructure/*, server/routes/ " +
        "файл называется так же, как класс внутри него (например AnswerSupportQueryUseCase — " +
        "в domain/usecase/AnswerSupportQueryUseCase.kt) — можно сразу читать по этому пути. " +
        "В model/ ЭТО НЕ РАБОТАЕТ: там HTTP DTO (*Request/*Response) сгруппированы по " +
        "несколько штук в общих файлах (например model/SearchResult.kt, model/Models.kt), " +
        "а не по одному классу на файл — для них угадывать путь по имени класса НЕЛЬЗЯ, " +
        "используй search_in_file_contents, если вообще нужно их смотреть. Обычно НЕ нужно: " +
        "по образцу уже существующих секций ARCHITECTURE.md (Day 31) описание фичи не " +
        "детализирует поля HTTP DTO — достаточно упомянуть route-файл.\n\n" +
        "У тебя не больше 10 итераций — расходуй их экономно:\n" +
        "- Строй путь к файлу сразу по конвенции выше и вызывай read_file напрямую; " +
        "НЕ обходи дерево директорий по одному уровню через list_directory, если можешь " +
        "предсказать путь — list_directory используй только когда действительно не знаешь, " +
        "в каком слое искать.\n" +
        "- Объединяй несколько tool_use в один ход, если можешь (например читай сразу " +
        "несколько файлов параллельно).\n" +
        "- Не проверяй один и тот же факт повторно другим тулом — одного релевантного " +
        "поиска обычно достаточно, не дублируй search_files и search_in_file_contents для " +
        "одного и того же символа.\n" +
        "- Не исследуй директории и файлы, не относящиеся напрямую к задаче.\n\n" +
        "Точность важнее полноты: не пиши в файлы и не утверждай ничего о конкретном классе/ " +
        "фиче на основе предположений или общих знаний о том, что 'обычно' бывает в таком " +
        "проекте — только на основе реально прочитанного через read_file содержимого. Если " +
        "на какую-то мелкую деталь (например точное имя конфиг-ключа или адаптера в DI-wiring) " +
        "не хватило бюджета итераций на чтение — опиши эту деталь обобщённо, без выдуманных " +
        "названий, а не пропускай задачу и не трать на неё все оставшиеся итерации.\n\n" +
        "Перед изменением файла всегда сначала прочитай его текущее содержимое. Если задача " +
        "требует изменить файл — доведи её до конца: реально вызови write_file с итоговым " +
        "содержимым в рамках этого же ответа, не останавливайся на текстовом плане. " +
        "Финальный текстовый ответ без tool_use означает, что задача полностью выполнена."

private val TOOLS = listOf(
    ToolDefinition(
        name = "list_directory",
        description = "Список файлов и подпапок в указанной директории проекта",
        params = listOf(ToolParam("path", "Путь к директории относительно корня проекта"))
    ),
    ToolDefinition(
        name = "read_file",
        description = "Прочитать содержимое файла целиком",
        params = listOf(ToolParam("path", "Путь к файлу относительно корня проекта"))
    ),
    ToolDefinition(
        name = "write_file",
        description = "Записать (перезаписать) содержимое файла. Перед вызовом всегда сначала прочитай текущее содержимое через read_file",
        params = listOf(
            ToolParam("path", "Путь к файлу относительно корня проекта"),
            ToolParam("content", "Новое полное содержимое файла")
        )
    ),
    ToolDefinition(
        name = "search_files",
        description = "Найти файлы, ИМЯ которых содержит указанную подстроку/шаблон " +
            "(поиск по именам файлов, НЕ по содержимому). Чтобы найти использование класса " +
            "или функции внутри кода — сначала list_directory/search_files по расширению " +
            "или директории, затем read_file для проверки содержимого",
        params = listOf(ToolParam("pattern", "Подстрока или шаблон в имени файла"))
    ),
    ToolDefinition(
        name = "search_in_file_contents",
        description = "Ищет ТЕКСТ внутри содержимого .kt файлов проекта, не по имени файла. " +
            "Используй, чтобы найти все места использования класса/функции/символа в коде " +
            "(например 'где используется ProjectDocsPort'). В отличие от search_files, " +
            "проверяет содержимое каждого файла, а не имя",
        params = listOf(ToolParam("pattern", "Текст или подстрока для поиска внутри содержимого файлов"))
    )
)

// Чистый domain usecase — не знает про HTTP/Ktor, принимает query и возвращает structured
// результат. Задел под Day 35 OrchestratorUseCase: можно вызывать напрямую как субагента,
// не только через POST /file-assistant.
class FileAssistantUseCase(
    private val fileToolPort: FileToolPort,
    private val agenticLlmPort: AgenticLlmPort
) {
    suspend fun execute(query: String): FileAssistantAnswer {
        val history = mutableListOf(AgentMessage(role = "user", content = listOf(AgentContentBlock.Text(query))))
        val toolCalls = mutableListOf<AgentToolCall>()

        repeat(MAX_ITERATIONS) { iteration ->
            when (val turn = agenticLlmPort.sendTurn(SYSTEM_PROMPT, history, TOOLS)) {
                is AgentTurn.FinalAnswer -> {
                    logger.debug("File assistant finished after {} iteration(s)", iteration + 1)
                    return FileAssistantAnswer(answer = turn.text, toolCalls = toolCalls)
                }

                is AgentTurn.ToolCallRequested -> {
                    history += AgentMessage(role = "assistant", content = turn.assistantContent)

                    val toolUseBlocks = turn.assistantContent.filterIsInstance<AgentContentBlock.ToolUse>()
                    val toolResults = toolUseBlocks.map { call ->
                        toolCalls += AgentToolCall(tool = call.name, args = call.input)
                        logger.debug("Tool call #{}: {} args={}", iteration + 1, call.name, call.input)
                        val (resultText, isError) = runCatching { executeTool(call.name, call.input) }
                            .fold(
                                onSuccess = { it to false },
                                onFailure = { e ->
                                    logger.warn("File tool {} failed: {}", call.name, e.message)
                                    "Error: ${e.message}" to true
                                }
                            )
                        logger.debug("Tool result #{}: {} isError={} resultLength={}", iteration + 1, call.name, isError, resultText.length)
                        AgentContentBlock.ToolResult(toolUseId = call.id, content = resultText, isError = isError)
                    }

                    history += AgentMessage(role = "user", content = toolResults)

                    val compressed = compressOlderToolResults(history)
                    history.clear()
                    history.addAll(compressed)
                }
            }
        }

        throw RagError.FileToolError("File assistant exceeded max iterations ($MAX_ITERATIONS) without a final answer")
    }

    // Сжимает ToolResult-блоки во ВСЕХ сообщениях истории, кроме самого последнего (только
    // что добавленного) — тот остаётся полным. Идемпотентно: повторный вызов на уже сжатом
    // блоке лишь укорачивает его сильнее (не ломается, просто теряет немного читаемости —
    // приемлемо для aggressive-порога ниже).
    private fun compressOlderToolResults(history: List<AgentMessage>): List<AgentMessage> {
        if (history.size <= 1) return history

        val totalChars = history.sumOf { message -> message.content.sumOf { it.approxChars() } }
        val previewChars = if (totalChars > MAX_HISTORY_CHARS) AGGRESSIVE_TOOL_RESULT_PREVIEW_CHARS else TOOL_RESULT_PREVIEW_CHARS

        val toolUseById = history.asSequence()
            .flatMap { it.content }
            .filterIsInstance<AgentContentBlock.ToolUse>()
            .associateBy { it.id }

        val lastIndex = history.size - 1
        return history.mapIndexed { index, message ->
            if (index == lastIndex) return@mapIndexed message
            if (message.content.none { it is AgentContentBlock.ToolResult }) return@mapIndexed message
            message.copy(
                content = message.content.map { block ->
                    if (block is AgentContentBlock.ToolResult) {
                        summarizeToolResult(block, toolUseById, previewChars)
                    } else {
                        block
                    }
                }
            )
        }
    }

    private fun summarizeToolResult(
        result: AgentContentBlock.ToolResult,
        toolUseById: Map<String, AgentContentBlock.ToolUse>,
        previewChars: Int
    ): AgentContentBlock.ToolResult {
        if (result.content.length <= previewChars) return result
        val call = toolUseById[result.toolUseId]
        val label = call?.let { "${it.name}(${it.input.entries.joinToString(", ") { (k, v) -> "$k=$v" }})" } ?: "tool"
        val preview = result.content.take(previewChars)
        val summary = "[$label] $preview... (полное содержимое было передано ранее и обработано)"
        return result.copy(content = summary)
    }

    private fun AgentContentBlock.approxChars(): Int = when (this) {
        is AgentContentBlock.Text -> text.length
        is AgentContentBlock.ToolUse -> name.length + input.values.sumOf { it.length }
        is AgentContentBlock.ToolResult -> content.length
    }

    private suspend fun executeTool(name: String, args: Map<String, String>): String = when (name) {
        "list_directory" -> fileToolPort.listDirectory(args["path"] ?: ".").joinToString("\n")
        "read_file" -> fileToolPort.readFile(args.getValue("path"))
        "write_file" -> {
            fileToolPort.writeFile(args.getValue("path"), args.getValue("content"))
            "OK"
        }
        "search_files" -> fileToolPort.searchFiles(args.getValue("pattern")).joinToString("\n")
        "search_in_file_contents" -> fileToolPort.searchInFileContents(args.getValue("pattern")).joinToString("\n")
        else -> throw RagError.FileToolError("Unknown tool: $name")
    }
}
