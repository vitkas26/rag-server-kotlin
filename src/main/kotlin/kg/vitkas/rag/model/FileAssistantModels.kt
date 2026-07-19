package kg.vitkas.rag.model

import kotlinx.serialization.Serializable

@Serializable
data class FileAssistantRequest(val query: String)

@Serializable
data class ToolCallDto(val tool: String, val args: Map<String, String>)

@Serializable
data class FileAssistantResponse(val answer: String, val toolCalls: List<ToolCallDto>)
