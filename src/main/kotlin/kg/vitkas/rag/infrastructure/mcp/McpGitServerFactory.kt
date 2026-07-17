package kg.vitkas.rag.infrastructure.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

fun buildMcpGitServer(): Server {
    val server = Server(
        serverInfo = Implementation(name = "rag-day21-git-tools", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )
    GitBranchToolHandler().register(server)
    return server
}
