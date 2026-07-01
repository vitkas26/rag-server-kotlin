package kg.vitkas.rag.config

import io.ktor.server.config.ApplicationConfig

data class OllamaConfig(val url: String, val model: String)

data class RagConfig(
    val dbPath: String,
    val mdPath: String,
    val fixedSize: Int,
    val fixedOverlap: Int,
    val sectionMax: Int,
    val topK: Int
)

data class AppConfig(val ollama: OllamaConfig, val rag: RagConfig) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig = AppConfig(
            ollama = OllamaConfig(
                url   = config.property("ollama.url").getString(),
                model = config.property("ollama.model").getString()
            ),
            rag = RagConfig(
                dbPath       = config.property("rag.dbPath").getString(),
                mdPath      = config.property("rag.mdPath").getString(),
                fixedSize    = config.property("rag.fixedSize").getString().toInt(),
                fixedOverlap = config.property("rag.fixedOverlap").getString().toInt(),
                sectionMax   = config.property("rag.sectionMax").getString().toInt(),
                topK         = config.property("rag.topK").getString().toInt()
            )
        )

        fun fromDefaults(): AppConfig = AppConfig(
            ollama = OllamaConfig(
                url   = System.getenv("OLLAMA_URL") ?: "http://localhost:11434/api/embeddings",
                model = "nomic-embed-text"
            ),
            rag = RagConfig(
                dbPath       = "rag_index.db",
                mdPath       = "NURAi_technical_document.md",
                fixedSize    = 500,
                fixedOverlap = 50,
                sectionMax   = 800,
                topK         = 5
            )
        )
    }
}
