package kg.vitkas.rag.domain.model

sealed class AgentTurn {
    data class ToolCallRequested(val assistantContent: List<AgentContentBlock>) : AgentTurn()
    data class FinalAnswer(val text: String) : AgentTurn()
}
