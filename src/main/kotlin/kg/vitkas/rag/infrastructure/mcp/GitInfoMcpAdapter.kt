package kg.vitkas.rag.infrastructure.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kg.vitkas.rag.domain.port.GitInfoPort
import kg.vitkas.rag.model.RagError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.infrastructure.mcp.GitInfoMcpAdapter")

private const val TOOL_NAME = "git_current_branch"

class GitInfoMcpAdapter(private val mcpBaseUrl: String) : GitInfoPort {
    private val httpClient = HttpClient { install(SSE) }
    private val client = Client(clientInfo = Implementation(name = "rag-day21-help-client", version = "1.0.0"))
    private val connectMutex = Mutex()
    private var connected = false

    private suspend fun ensureConnected() {
        if (connected) return
        connectMutex.withLock {
            if (connected) return@withLock
            logger.info("Connecting to MCP git server at {}", mcpBaseUrl)
            client.connect(StreamableHttpClientTransport(client = httpClient, url = mcpBaseUrl))
            connected = true
        }
    }

    override suspend fun currentBranch(): String {
        try {
            ensureConnected()
            val result = client.callTool(name = TOOL_NAME, arguments = emptyMap())
            return result.content.filterIsInstance<TextContent>().firstOrNull()?.text
                ?: throw RagError.GitError("MCP $TOOL_NAME returned no text content")
        } catch (e: RagError.GitError) {
            throw e
        } catch (e: Exception) {
            throw RagError.GitError("MCP $TOOL_NAME call failed: ${e.message}", e)
        }
    }

    fun close() {
        httpClient.close()
    }
}
