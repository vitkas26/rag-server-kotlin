package kg.vitkas.rag.infrastructure.git

import kg.vitkas.rag.model.RagError
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class GitDiffAdapterTest {

    private val repoPath = System.getProperty("user.dir")
    private val adapter = GitDiffAdapter(repoPath)

    @Test
    fun `rejects flag-shaped ref instead of passing it to git`() = runTest {
        assertFailsWith<RagError.GitError> {
            adapter.getDiff("--output=/tmp/pwned", "HEAD")
        }
    }

    @Test
    fun `rejects branch name as ref`() = runTest {
        assertFailsWith<RagError.GitError> {
            adapter.changedFiles("main", "feature/foo")
        }
    }

    @Test
    fun `accepts real commit SHA and runs git diff`() = runTest {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(File(repoPath))
            .start()
        val head = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()

        assertEquals("", adapter.getDiff(head, head))
    }
}
