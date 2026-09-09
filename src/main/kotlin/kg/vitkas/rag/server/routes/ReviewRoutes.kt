package kg.vitkas.rag.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kg.vitkas.rag.domain.port.DiffPort
import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.domain.port.ProjectDocsPort
import kg.vitkas.rag.domain.usecase.ReviewPullRequestUseCase
import kg.vitkas.rag.model.ReviewRequest
import kg.vitkas.rag.model.ReviewResponse

fun Route.reviewRoutes(
    projectDocsPort: ProjectDocsPort,
    codeContextPort: ProjectDocsPort,
    llmPort: LlmPort,
    diffPort: DiffPort
) {
    post("/review-pr") {
        val request = call.receive<ReviewRequest>()
        val useCase = ReviewPullRequestUseCase(diffPort, projectDocsPort, codeContextPort, llmPort)

        val review = useCase.execute(request.base, request.head)

        call.respond(
            HttpStatusCode.OK,
            ReviewResponse(
                bugs = review.bugs,
                architectureIssues = review.architectureIssues,
                recommendations = review.recommendations,
                sources = review.sources
            )
        )
    }
}
