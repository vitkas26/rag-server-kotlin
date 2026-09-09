package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.domain.usecase.AnswerSupportQueryUseCase
import kg.vitkas.rag.model.SupportRequest
import kg.vitkas.rag.model.SupportResponse

fun Route.supportRoutes(useCase: AnswerSupportQueryUseCase) {
    post("/support") {
        val request = call.receive<SupportRequest>()
        val answer = useCase.execute(request.query, request.ticketId)
        call.respond(
            HttpStatusCode.OK,
            SupportResponse(answer = answer.answer, sources = answer.sources, ticketFound = answer.ticketFound)
        )
    }
}
