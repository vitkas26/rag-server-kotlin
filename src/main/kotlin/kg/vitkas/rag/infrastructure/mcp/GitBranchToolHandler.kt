package kg.vitkas.rag.infrastructure.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.infrastructure.mcp.GitBranchToolHandler")

class GitBranchToolHandler {
    fun register(server: Server) {
        server.addTool(
            name = "git_current_branch",
            description = "Возвращает имя текущей git-ветки локального репозитория проекта",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList())
        ) { _ ->
            val branch = runCatching {
                val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                output
            }.getOrElse { e ->
                logger.warn("git rev-parse failed", e)
                "unknown"
            }
            CallToolResult(content = listOf(TextContent(branch)))
        }
    }
}
