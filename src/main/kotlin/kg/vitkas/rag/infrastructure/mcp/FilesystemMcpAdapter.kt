package kg.vitkas.rag.infrastructure.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kg.vitkas.rag.domain.port.FileToolPort
import kg.vitkas.rag.model.RagError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.infrastructure.mcp.FilesystemMcpAdapter")

// Директории, которые не имеет смысла обходить при поиске по содержимому — служебные/
// генерируемые, могут быть огромными или содержать бинарные файлы.
private val SKIP_DIRS = setOf(".git", ".gradle", ".idea", ".kotlin", "build", ".claude", ".github")

// implements FileToolPort — MCP-клиент к официальному @modelcontextprotocol/server-filesystem,
// запущенному как ОТДЕЛЬНЫЙ npm-процесс (в отличие от GitInfoMcpAdapter, который ходит на
// MCP-сервер, смонтированный в этом же Ktor-приложении по HTTP). Транспорт — stdio
// (StdioClientTransport из kotlin-sdk-client), т.к. официальный filesystem-сервер общается
// по stdin/stdout, не поднимает HTTP. Ограничение доступа к [repoPath] обеспечивает сам
// сервер (единственный разрешённый каталог передаётся аргументом при старте) — свою защиту
// от выхода за пределы репозитория в Kotlin-коде не дублируем.
class FilesystemMcpAdapter(command: String, private val repoPath: String) : FileToolPort {
    private val commandParts = command.trim().split(Regex("\\s+")) + repoPath
    private val client = Client(clientInfo = Implementation(name = "rag-day21-file-assistant-client", version = "1.0.0"))
    private val connectMutex = Mutex()
    private var connected = false
    private var process: Process? = null

    private suspend fun ensureConnected() {
        if (connected) return
        connectMutex.withLock {
            if (connected) return@withLock
            logger.info("Starting MCP filesystem server: {}", commandParts.joinToString(" "))
            val proc = ProcessBuilder(commandParts).start()
            process = proc
            val transport = StdioClientTransport(
                input = proc.inputStream.asSource().buffered(),
                output = proc.outputStream.asSink().buffered(),
                error = proc.errorStream.asSource().buffered(),
                classifyStderr = { StdioClientTransport.StderrSeverity.DEBUG }
            )
            client.connect(transport)
            connected = true
        }
    }

    private suspend fun callToolText(toolName: String, arguments: Map<String, Any?>): String {
        ensureConnected()
        val result = client.callTool(name = toolName, arguments = arguments)
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        // MCP-протокол сигналит ошибку тула (например "файл не найден") через isError=true
        // на самом CallToolResult, а не через exception/протокольную ошибку — content при
        // этом валидный TextContent с текстом ошибки. Без этой проверки такая ошибка молча
        // проходила как "успешное" чтение с текстом-объяснением вместо содержимого файла.
        if (result.isError == true) {
            throw RagError.FileToolError("MCP $toolName reported an error: $text")
        }
        return text
    }

    private suspend fun <T> wrap(toolName: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: RagError.FileToolError) {
            throw e
        } catch (e: Exception) {
            throw RagError.FileToolError("MCP $toolName call failed: ${e.message}", e)
        }

    override suspend fun listDirectory(path: String): List<String> = wrap("list_directory") {
        callToolText("list_directory", mapOf("path" to path)).lines().filter { it.isNotBlank() }
    }

    override suspend fun readFile(path: String): String = wrap("read_file") {
        callToolText("read_file", mapOf("path" to path))
    }

    override suspend fun writeFile(path: String, content: String) {
        wrap("write_file") { callToolText("write_file", mapOf("path" to path, "content" to content)) }
    }

    override suspend fun searchFiles(pattern: String): List<String> = wrap("search_files") {
        callToolText("search_files", mapOf("path" to repoPath, "pattern" to pattern)).lines().filter { it.isNotBlank() }
    }

    // НЕ MCP-тул — официальный сервер такого не даёт. Локальная Kotlin-логика поверх уже
    // существующих listDirectory/readFile: рекурсивный обход .kt файлов + contains(pattern).
    override suspend fun searchInFileContents(pattern: String): List<String> = wrap("search_in_file_contents") {
        val matches = mutableListOf<String>()
        suspend fun walk(dir: String) {
            for (entry in listDirectory(dir)) {
                val name = entry.substringAfter("] ", missingDelimiterValue = "").trim()
                if (name.isBlank()) continue
                val childPath = if (dir == ".") name else "$dir/$name"
                when {
                    entry.startsWith("[DIR]") -> if (name !in SKIP_DIRS) walk(childPath)
                    entry.startsWith("[FILE]") && name.endsWith(".kt") -> {
                        val content = runCatching { readFile(childPath) }.getOrNull()
                        if (content != null && content.contains(pattern)) matches += childPath
                    }
                }
            }
        }
        walk(".")
        matches
    }

    fun close() {
        process?.destroy()
    }
}
