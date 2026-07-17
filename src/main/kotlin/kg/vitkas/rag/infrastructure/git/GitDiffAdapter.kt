package kg.vitkas.rag.infrastructure.git

import kg.vitkas.rag.domain.port.DiffPort
import kg.vitkas.rag.model.RagError
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.infrastructure.git.GitDiffAdapter")

class GitDiffAdapter(private val repoPath: String) : DiffPort {

    override suspend fun getDiff(base: String, head: String): String =
        runGit("diff", "$base...$head")

    override suspend fun changedFiles(base: String, head: String): List<String> =
        runGit("diff", "--name-only", "$base...$head")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun runGit(vararg args: String): String {
        logger.debug("Running git {} in {}", args.joinToString(" "), repoPath)
        val process = runCatching {
            ProcessBuilder("git", *args)
                .directory(File(repoPath))
                .start()
        }.getOrElse { e ->
            throw RagError.GitError("Failed to start git process: ${e.message}", e)
        }

        val output = process.inputStream.bufferedReader().readText()
        val errorOutput = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RagError.GitError("git ${args.joinToString(" ")} failed (exit $exitCode): $errorOutput")
        }
        return output
    }
}
