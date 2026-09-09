package kg.vitkas.rag.domain.port

import kg.vitkas.rag.domain.model.AgentMessage
import kg.vitkas.rag.domain.model.AgentTurn
import kg.vitkas.rag.domain.model.ToolDefinition

interface AgenticLlmPort {
    suspend fun sendTurn(system: String, history: List<AgentMessage>, tools: List<ToolDefinition>): AgentTurn
}
