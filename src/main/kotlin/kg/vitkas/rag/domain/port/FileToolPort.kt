package kg.vitkas.rag.domain.port

interface FileToolPort {
    suspend fun listDirectory(path: String): List<String>
    suspend fun readFile(path: String): String
    suspend fun writeFile(path: String, content: String)
    suspend fun searchFiles(pattern: String): List<String>
    // Официальный MCP filesystem server ищет только по ИМЕНИ файла (search_files, glob) —
    // содержимое не грепает. Этот метод ищет ТЕКСТ внутри .kt файлов.
    suspend fun searchInFileContents(pattern: String): List<String>
}
