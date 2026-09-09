package kg.vitkas.rag.domain.usecase

import kg.vitkas.rag.domain.model.AgentContentBlock
import kg.vitkas.rag.domain.model.AgentMessage
import kg.vitkas.rag.domain.model.AgentTurn
import kg.vitkas.rag.domain.model.ToolDefinition
import kg.vitkas.rag.domain.port.AgenticLlmPort
import kg.vitkas.rag.domain.port.FileToolPort
import kg.vitkas.rag.model.RagError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class FakeFileToolPort : FileToolPort {
    val listCalls = mutableListOf<String>()
    val readCalls = mutableListOf<String>()
    val writeCalls = mutableListOf<Pair<String, String>>()
    val searchCalls = mutableListOf<String>()

    override suspend fun listDirectory(path: String): List<String> {
        listCalls += path
        return listOf("a.kt", "b.kt")
    }

    override suspend fun readFile(path: String): String {
        readCalls += path
        return "content of $path"
    }

    override suspend fun writeFile(path: String, content: String) {
        writeCalls += path to content
    }

    override suspend fun searchFiles(pattern: String): List<String> {
        searchCalls += pattern
        return listOf("Found.kt")
    }

    val contentSearchCalls = mutableListOf<String>()

    override suspend fun searchInFileContents(pattern: String): List<String> {
        contentSearchCalls += pattern
        return listOf("Found.kt")
    }
}

private class FakeAgenticLlmPort(private val turns: List<AgentTurn>) : AgenticLlmPort {
    var callCount = 0
        private set
    val historySnapshots = mutableListOf<List<AgentMessage>>()

    override suspend fun sendTurn(system: String, history: List<AgentMessage>, tools: List<ToolDefinition>): AgentTurn {
        historySnapshots += history
        val turn = turns.getOrElse(callCount) { turns.last() }
        callCount++
        return turn
    }
}

class FileAssistantUseCaseTest {

    @Test
    fun `tool_use then tool_use then final answer returns toolCalls in order and terminates`() = runTest {
        val fileToolPort = FakeFileToolPort()
        val turn1 = AgentTurn.ToolCallRequested(
            listOf(AgentContentBlock.ToolUse("id1", "list_directory", mapOf("path" to ".")))
        )
        val turn2 = AgentTurn.ToolCallRequested(
            listOf(AgentContentBlock.ToolUse("id2", "read_file", mapOf("path" to "a.kt")))
        )
        val turn3 = AgentTurn.FinalAnswer("done")
        val llmPort = FakeAgenticLlmPort(listOf(turn1, turn2, turn3))
        val useCase = FileAssistantUseCase(fileToolPort, llmPort)

        val answer = useCase.execute("do something")

        assertEquals("done", answer.answer)
        assertEquals(listOf("list_directory", "read_file"), answer.toolCalls.map { it.tool })
        assertEquals(3, llmPort.callCount)
        assertEquals(listOf("."), fileToolPort.listCalls)
        assertEquals(listOf("a.kt"), fileToolPort.readCalls)
    }

    @Test
    fun `immediate final answer makes a single LLM call and no tool calls`() = runTest {
        val fileToolPort = FakeFileToolPort()
        val llmPort = FakeAgenticLlmPort(listOf(AgentTurn.FinalAnswer("no tools needed")))
        val useCase = FileAssistantUseCase(fileToolPort, llmPort)

        val answer = useCase.execute("just answer")

        assertEquals("no tools needed", answer.answer)
        assertTrue(answer.toolCalls.isEmpty())
        assertEquals(1, llmPort.callCount)
    }

    @Test
    fun `exceeding max iterations throws FileToolError`() = runTest {
        val fileToolPort = FakeFileToolPort()
        val infiniteTurn = AgentTurn.ToolCallRequested(
            listOf(AgentContentBlock.ToolUse("id", "list_directory", mapOf("path" to ".")))
        )
        val llmPort = FakeAgenticLlmPort(List(20) { infiniteTurn })
        val useCase = FileAssistantUseCase(fileToolPort, llmPort)

        assertFailsWith<RagError.FileToolError> { useCase.execute("loop forever") }
        assertEquals(10, llmPort.callCount)
    }

    @Test
    fun `failed tool execution is reported back as an error tool_result, not thrown`() = runTest {
        val fileToolPort = object : FileToolPort {
            override suspend fun listDirectory(path: String) = emptyList<String>()
            override suspend fun readFile(path: String): String = throw RagError.FileToolError("file not found")
            override suspend fun writeFile(path: String, content: String) {}
            override suspend fun searchFiles(pattern: String) = emptyList<String>()
            override suspend fun searchInFileContents(pattern: String) = emptyList<String>()
        }
        val turn1 = AgentTurn.ToolCallRequested(
            listOf(AgentContentBlock.ToolUse("id1", "read_file", mapOf("path" to "missing.kt")))
        )
        val turn2 = AgentTurn.FinalAnswer("recovered")
        val llmPort = FakeAgenticLlmPort(listOf(turn1, turn2))
        val useCase = FileAssistantUseCase(fileToolPort, llmPort)

        val answer = useCase.execute("read a missing file")

        assertEquals("recovered", answer.answer)
        val toolResultMessage = llmPort.historySnapshots[1].last()
        val toolResult = toolResultMessage.content.filterIsInstance<AgentContentBlock.ToolResult>().single()
        assertTrue(toolResult.isError)
    }

    @Test
    fun `older tool results get compressed, only the newest stays full`() = runTest {
        val fileToolPort = object : FileToolPort {
            override suspend fun listDirectory(path: String) = emptyList<String>()
            override suspend fun readFile(path: String): String = "X".repeat(500)
            override suspend fun writeFile(path: String, content: String) {}
            override suspend fun searchFiles(pattern: String) = emptyList<String>()
            override suspend fun searchInFileContents(pattern: String) = emptyList<String>()
        }
        val readTurns = (1..5).map { i ->
            AgentTurn.ToolCallRequested(
                listOf(AgentContentBlock.ToolUse("id$i", "read_file", mapOf("path" to "file$i.kt")))
            )
        }
        val llmPort = FakeAgenticLlmPort(readTurns + AgentTurn.FinalAnswer("done"))
        val useCase = FileAssistantUseCase(fileToolPort, llmPort)

        useCase.execute("read many files")

        // История, переданная на ПОСЛЕДНИЙ вызов (перед FinalAnswer) — это post-compression
        // состояние после всех 5 раундов read_file.
        val finalHistory = llmPort.historySnapshots.last()
        val toolResultBlocks = finalHistory.flatMap { it.content }.filterIsInstance<AgentContentBlock.ToolResult>()
        assertEquals(5, toolResultBlocks.size)

        val older = toolResultBlocks.dropLast(1)
        val newest = toolResultBlocks.last()
        assertTrue(older.all { it.content.length < 500 }, "older tool results must be compressed, not full-length")
        assertEquals(500, newest.content.length, "the newest tool result must stay untouched")

        // Кумулятивный объём истории не растёт линейно с числом итераций — при "наивном"
        // подходе (полная история каждый раз) здесь было бы 5 * 500 = 2500 символов только
        // в tool_result-блоках; со сжатием — заметно меньше.
        val totalToolResultChars = toolResultBlocks.sumOf { it.content.length }
        assertTrue(totalToolResultChars < 5 * 500, "compression must reduce cumulative history size")
    }

    @Test
    fun `history exceeding the size threshold triggers more aggressive compression`() = runTest {
        // Компрессия срабатывает инкрементально на КАЖДОЙ итерации, поэтому суммарный объём
        // "сырой" истории никогда органически не доходит до большого числа — старые раунды
        // уже сжаты к моменту, когда добавляется новый. Чтобы реально пробить порог
        // MAX_HISTORY_CHARS (50000), одного раунда должно хватать самого по себе: используем
        // контент size 60000 — уже после первого раунда total (60000 raw, ещё не сжат, т.к.
        // это единственное/последнее сообщение) не компрессируется; когда прилетает второй
        // раунд, totalChars(60000 raw #1 + 60000 raw #2) далеко за порогом — #1 сжимается
        // агрессивно (60 символов вместо обычных 200).
        val bigContent = "Y".repeat(60_000)
        val fileToolPort = object : FileToolPort {
            override suspend fun listDirectory(path: String) = emptyList<String>()
            override suspend fun readFile(path: String): String = bigContent
            override suspend fun writeFile(path: String, content: String) {}
            override suspend fun searchFiles(pattern: String) = emptyList<String>()
            override suspend fun searchInFileContents(pattern: String) = emptyList<String>()
        }
        val readTurns = (1..2).map { i ->
            AgentTurn.ToolCallRequested(
                listOf(AgentContentBlock.ToolUse("id$i", "read_file", mapOf("path" to "big$i.kt")))
            )
        }
        val llmPort = FakeAgenticLlmPort(readTurns + AgentTurn.FinalAnswer("done"))
        val useCase = FileAssistantUseCase(fileToolPort, llmPort)

        useCase.execute("read big files")

        val finalHistory = llmPort.historySnapshots.last()
        val toolResultBlocks = finalHistory.flatMap { it.content }.filterIsInstance<AgentContentBlock.ToolResult>()
        val olderToolResults = toolResultBlocks.dropLast(1)
        // Обычный (не агрессивный) компресс даёт ~283 символа (200 preview + label + suffix) —
        // порог в 200 надёжно отличает "агрессивно сжато" (~145) от "сжато нормально" (~283),
        // не завязываясь на точную арифметику длины лейбла/суффикса.
        assertTrue(
            olderToolResults.all { it.content.length < 200 },
            "large accumulated history must trigger more aggressive compression than the normal 200-char preview"
        )
    }
}
