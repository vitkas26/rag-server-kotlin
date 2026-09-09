package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.domain.usecase.AnswerHelpQueryUseCase
import kg.vitkas.rag.model.HelpRequest
import kg.vitkas.rag.model.HelpResponse

fun Route.helpRoutes(useCase: AnswerHelpQueryUseCase) {
    post("/help") {
        val request = call.receive<HelpRequest>()
        val answer = useCase.execute(request.query)
        call.respond(HttpStatusCode.OK, HelpResponse(answer = answer.text, sources = answer.sources))
    }
}
