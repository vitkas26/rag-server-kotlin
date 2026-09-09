package kg.vitkas.rag.pipeline

import kg.vitkas.rag.config.RagConfig
import kg.vitkas.rag.model.Chunk
import kg.vitkas.rag.model.Section
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.pipeline.Chunker")

fun extractSections(text: String): List<Section> {
    val regex = Regex("""##\s+(\d+)\.\s+(.+?)(?=\n##\s+\d+\.|$)""", RegexOption.DOT_MATCHES_ALL)
    val sections = regex.findAll(text).map { m ->
        Section(
            number  = m.groupValues[1].toInt(),
            title   = "${m.groupValues[1]}. ${m.groupValues[2].lines().first().trim()}",
            content = m.value.trim()
        )
    }.toList()
    logger.debug("Found {} sections", sections.size)
    return sections
}

fun chunkByFixedSize(text: String, config: RagConfig, source: String = config.mdPath): List<Chunk> {
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    val chunks = mutableListOf<Chunk>()
    var index = 0
    var chunkIndex = 0

    while (index < words.size) {
        val end = minOf(index + config.fixedSize, words.size)
        val chunkWords = words.subList(index, end)
        chunks.add(
            Chunk(
                chunkId   = "fixed_$chunkIndex",
                source    = source,
                strategy  = "fixed_size",
                title     = "Fixed chunk $chunkIndex",
                section   = "",
                wordCount = chunkWords.size,
                content   = chunkWords.joinToString(" ")
            )
        )
        chunkIndex++
        index += config.fixedSize - config.fixedOverlap
        if (index >= words.size) break
    }

    logger.debug("Strategy A (fixed_size): {} chunks", chunks.size)
    return chunks
}

fun chunkBySection(sections: List<Section>, config: RagConfig, source: String = config.mdPath): List<Chunk> {
    val chunks = mutableListOf<Chunk>()

    sections.forEachIndexed { sectionIndex, section ->
        val words = section.content.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (words.size <= config.sectionMax) {
            chunks.add(
                Chunk(
                    chunkId   = "section_${sectionIndex}_0",
                    source    = source,
                    strategy  = "by_structure",
                    title     = section.title,
                    section   = "Часть ${section.number}",
                    wordCount = words.size,
                    content   = section.content
                )
            )
        } else {
            var subIndex = 0
            var start = 0
            while (start < words.size) {
                val end = minOf(start + config.sectionMax, words.size)
                val subWords = words.subList(start, end)
                chunks.add(
                    Chunk(
                        chunkId   = "section_${sectionIndex}_$subIndex",
                        source    = source,
                        strategy  = "by_structure",
                        title     = "${section.title} (часть ${subIndex + 1})",
                        section   = "Часть ${section.number}",
                        wordCount = subWords.size,
                        content   = subWords.joinToString(" ")
                    )
                )
                start += config.sectionMax
                subIndex++
            }
        }
    }

    logger.debug("Strategy B (by_structure): {} chunks", chunks.size)
    return chunks
}
