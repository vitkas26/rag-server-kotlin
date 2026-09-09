package kg.vitkas.rag.domain.model

data class ToolParam(val name: String, val description: String, val required: Boolean = true)

data class ToolDefinition(val name: String, val description: String, val params: List<ToolParam>)
