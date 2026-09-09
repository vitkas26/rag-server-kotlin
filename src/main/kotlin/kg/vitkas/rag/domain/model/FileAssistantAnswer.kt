package kg.vitkas.rag.domain.model

data class FileAssistantAnswer(val answer: String, val toolCalls: List<AgentToolCall>)
