package kg.vitkas.rag.domain.usecase

import kg.vitkas.rag.domain.model.DocChunk
import kg.vitkas.rag.domain.port.GitInfoPort
import kg.vitkas.rag.domain.port.LlmPort
import kg.vitkas.rag.domain.port.ProjectDocsPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class FakeGitInfoPort(private val branch: String = "main") : GitInfoPort {
    var callCount = 0
        private set

    override suspend fun currentBranch(): String {
        callCount++
        return branch
    }
}

private class FakeProjectDocsPort(
    private val chunks: List<DocChunk> = listOf(DocChunk(content = "Package layout: config/model/pipeline/server", source = "README.md", score = 0.9))
) : ProjectDocsPort {
    var callCount = 0
        private set

    override suspend fun search(query: String): List<DocChunk> {
        callCount++
        return chunks
    }
}

private class FakeLlmPort : LlmPort {
    var lastSystem: String? = null
    var lastUserMessage: String? = null

    override suspend fun complete(system: String, userMessage: String): String {
        lastSystem = system
        lastUserMessage = userMessage
        return "fake answer"
    }
}

class AnswerHelpQueryUseCaseTest {

    @Test
    fun `git question routes to GitInfoPort only`() = runTest {
        val gitPort = FakeGitInfoPort(branch = "ai_advent_day31_dev_assistant")
        val docsPort = FakeProjectDocsPort()
        val llmPort = FakeLlmPort()
        val useCase = AnswerHelpQueryUseCase(gitPort, docsPort, llmPort)

        val answer = useCase.execute("На какой git-ветке сейчас проект?")

        assertEquals(1, gitPort.callCount)
        assertEquals(0, docsPort.callCount)
        assertTrue(answer.sources.any { it == "git:branch=ai_advent_day31_dev_assistant" })
    }

    @Test
    fun `architecture question routes to ProjectDocsPort only`() = runTest {
        val gitPort = FakeGitInfoPort()
        val docsPort = FakeProjectDocsPort()
        val llmPort = FakeLlmPort()
        val useCase = AnswerHelpQueryUseCase(gitPort, docsPort, llmPort)

        val answer = useCase.execute("Как устроена структура пакетов проекта?")

        assertEquals(0, gitPort.callCount)
        assertEquals(1, docsPort.callCount)
        assertTrue(answer.sources.any { it == "README.md" })
    }

    @Test
    fun `neutral question falls back to docs context`() = runTest {
        val gitPort = FakeGitInfoPort()
        val docsPort = FakeProjectDocsPort()
        val llmPort = FakeLlmPort()
        val useCase = AnswerHelpQueryUseCase(gitPort, docsPort, llmPort)

        useCase.execute("Привет, расскажи о проекте")

        assertEquals(0, gitPort.callCount)
        assertEquals(1, docsPort.callCount)
    }
}
