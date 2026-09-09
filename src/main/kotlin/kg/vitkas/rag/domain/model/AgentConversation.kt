package kg.vitkas.rag.domain.model

// Провайдер-независимое представление истории диалога с агентным LLM — не завязано
// на wire-формат конкретного провайдера (Anthropic content blocks и т.п.).
sealed class AgentContentBlock {
    data class Text(val text: String) : AgentContentBlock()
    data class ToolUse(val id: String, val name: String, val input: Map<String, String>) : AgentContentBlock()
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : AgentContentBlock()
}

data class AgentMessage(val role: String, val content: List<AgentContentBlock>)
