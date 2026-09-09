package kg.vitkas.rag.domain.usecase

import kg.vitkas.rag.domain.model.DocChunk
import kg.vitkas.rag.domain.port.DiffPort
import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.domain.port.ProjectDocsPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class FakeReviewDiffPort(
    private val diff: String = "diff --git a/Foo.kt b/Foo.kt\n+ fun foo() {}",
    private val files: List<String> = listOf("Foo.kt")
) : DiffPort {
    var lastBase: String? = null
        private set
    var lastHead: String? = null
        private set

    override suspend fun getDiff(base: String, head: String): String {
        lastBase = base
        lastHead = head
        return diff
    }

    override suspend fun changedFiles(base: String, head: String): List<String> {
        lastBase = base
        lastHead = head
        return files
    }
}

private class FakeReviewDocsPort(private val chunks: List<DocChunk>) : ProjectDocsPort {
    var callCount = 0
        private set

    override suspend fun search(query: String): List<DocChunk> {
        callCount++
        return chunks
    }
}

private class FakeReviewLlmPort(private val response: String) : LlmPort {
    var lastSystem: String? = null
        private set
    var lastUserMessage: String? = null
        private set

    override suspend fun complete(system: String, userMessage: String): String {
        lastSystem = system
        lastUserMessage = userMessage
        return response
    }
}

class ReviewPullRequestUseCaseTest {

    private val llmResponse = """
        ## Баги
        - null-check отсутствует в Foo.kt:12

        ## Архитектурные проблемы
        не найдено

        ## Рекомендации
        - добавить unit-тест на foo()
    """.trimIndent()

    @Test
    fun `parses three sections from LLM response`() = runTest {
        val diffPort = FakeReviewDiffPort()
        val docsPort = FakeReviewDocsPort(listOf(DocChunk("arch content", "ARCHITECTURE.md", 0.9)))
        val codePort = FakeReviewDocsPort(listOf(DocChunk("code content", "Foo.kt", 0.8)))
        val llmPort = FakeReviewLlmPort(llmResponse)
        val useCase = ReviewPullRequestUseCase(diffPort, docsPort, codePort, llmPort)

        val review = useCase.execute(base = "main", head = "feature")

        assertEquals(listOf("null-check отсутствует в Foo.kt:12"), review.bugs)
        assertEquals(emptyList(), review.architectureIssues)
        assertEquals(listOf("добавить unit-тест на foo()"), review.recommendations)
    }

    @Test
    fun `calls diffPort with correct base and head`() = runTest {
        val diffPort = FakeReviewDiffPort()
        val docsPort = FakeReviewDocsPort(emptyList())
        val codePort = FakeReviewDocsPort(emptyList())
        val llmPort = FakeReviewLlmPort(llmResponse)
        val useCase = ReviewPullRequestUseCase(diffPort, docsPort, codePort, llmPort)

        useCase.execute(base = "abc123", head = "def456")

        assertEquals("abc123", diffPort.lastBase)
        assertEquals("def456", diffPort.lastHead)
    }

    @Test
    fun `aggregates sources from both RAG ports`() = runTest {
        val diffPort = FakeReviewDiffPort()
        val docsPort = FakeReviewDocsPort(listOf(DocChunk("arch content", "ARCHITECTURE.md", 0.9)))
        val codePort = FakeReviewDocsPort(listOf(DocChunk("code content", "Foo.kt", 0.8)))
        val llmPort = FakeReviewLlmPort(llmResponse)
        val useCase = ReviewPullRequestUseCase(diffPort, docsPort, codePort, llmPort)

        val review = useCase.execute(base = "main", head = "feature")

        assertTrue(review.sources.contains("ARCHITECTURE.md"))
        assertTrue(review.sources.contains("Foo.kt"))
        assertEquals(1, docsPort.callCount)
        assertEquals(1, codePort.callCount)
    }
}
