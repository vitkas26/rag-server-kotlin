package kg.vitkas.rag.domain.model

data class AgentToolCall(val tool: String, val args: Map<String, String>)
