package kg.vitkas.rag.config

import io.ktor.server.config.ApplicationConfig

data class OllamaConfig(val url: String, val model: String, val generationModel: String)

data class RagConfig(
    val dbPath: String,
    val mdPath: String,
    val fixedSize: Int,
    val fixedOverlap: Int,
    val sectionMax: Int,
    val topK: Int
)

data class AnthropicConfig(
    val apiKey: String,
    val url: String,
    val model: String,
    val maxTokens: Int,
    val version: String
)

data class AppConfig(val ollama: OllamaConfig, val rag: RagConfig, val anthropic: AnthropicConfig) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig = AppConfig(
            ollama = OllamaConfig(
                url             = config.property("ollama.url").getString(),
                model           = config.property("ollama.model").getString(),
                generationModel = config.property("ollama.generationModel").getString()
            ),
            rag = RagConfig(
                dbPath       = config.property("rag.dbPath").getString(),
                mdPath      = config.property("rag.mdPath").getString(),
                fixedSize    = config.property("rag.fixedSize").getString().toInt(),
                fixedOverlap = config.property("rag.fixedOverlap").getString().toInt(),
                sectionMax   = config.property("rag.sectionMax").getString().toInt(),
                topK         = config.property("rag.topK").getString().toInt()
            ),
            anthropic = AnthropicConfig(
                apiKey    = config.property("anthropic.apiKey").getString(),
                url       = config.property("anthropic.url").getString(),
                model     = config.property("anthropic.model").getString(),
                maxTokens = config.property("anthropic.maxTokens").getString().toInt(),
                version   = config.property("anthropic.version").getString()
            )
        )

        fun fromDefaults(): AppConfig = AppConfig(
            ollama = OllamaConfig(
                url             = System.getenv("OLLAMA_URL") ?: "http://localhost:11434/api/embeddings",
                model           = "nomic-embed-text",
                generationModel = System.getenv("OLLAMA_GENERATION_MODEL") ?: "qwen2.5:7b-instruct"
            ),
            rag = RagConfig(
                dbPath       = "rag_index.db",
                mdPath       = "syucai_knowledge_base.md",
                fixedSize    = 500,
                fixedOverlap = 50,
                sectionMax   = 800,
                topK         = 5
            ),
            anthropic = AnthropicConfig(
                apiKey    = System.getenv("ANTHROPIC_API_KEY") ?: "",
                url       = "https://api.anthropic.com/v1/messages",
                model     = "claude-haiku-4-5-20251001",
                maxTokens = 1024,
                version   = "2023-06-01"
            )
        )
    }
}
