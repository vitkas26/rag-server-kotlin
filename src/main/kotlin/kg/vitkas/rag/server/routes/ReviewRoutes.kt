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
import kg.vitkas.rag.infrastructure.git.GitDiffAdapter
import kg.vitkas.rag.model.ReviewRequest
import kg.vitkas.rag.model.ReviewResponse

fun Route.reviewRoutes(
    projectDocsPort: ProjectDocsPort,
    codeContextPort: ProjectDocsPort,
    llmPort: LlmPort,
    defaultRepoPath: String
) {
    post("/review-pr") {
        val request = call.receive<ReviewRequest>()
        val repoPath = request.repoPath ?: defaultRepoPath

        // GitDiffAdapter не держит соединений/состояния (в отличие от GitInfoMcpAdapter) —
        // дёшево создать под конкретный repoPath запроса, а не завязываться на один
        // wired-at-startup инстанс с фиксированным путём.
        val diffPort: DiffPort = GitDiffAdapter(repoPath)
        val useCase = ReviewPullRequestUseCase(diffPort, projectDocsPort, codeContextPort, llmPort)

        val review = useCase.execute(request.base, request.head, repoPath)

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
