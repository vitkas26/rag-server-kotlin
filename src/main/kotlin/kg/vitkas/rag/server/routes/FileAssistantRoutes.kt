package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.domain.usecase.FileAssistantUseCase
import kg.vitkas.rag.model.FileAssistantRequest
import kg.vitkas.rag.model.FileAssistantResponse
import kg.vitkas.rag.model.ToolCallDto

fun Route.fileAssistantRoutes(useCase: FileAssistantUseCase) {
    post("/file-assistant") {
        val request = call.receive<FileAssistantRequest>()
        val result = useCase.execute(request.query)
        call.respond(
            HttpStatusCode.OK,
            FileAssistantResponse(
                answer = result.answer,
                toolCalls = result.toolCalls.map { ToolCallDto(it.tool, it.args) }
            )
        )
    }
}
