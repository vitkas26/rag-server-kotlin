package kg.vitkas.rag.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Проверяет ТОЛЬКО wire-формат (сериализацию) — без живого вызова Anthropic API
// (баланс исчерпан на момент реализации prompt caching, Day 34.1). Формат основан на
// документации Anthropic (shared/prompt-caching.md), но фактическое попадание в кэш
// (cache_creation_input_tokens/cache_read_input_tokens в реальном ответе) не подтверждено.
class AnthropicAgentModelsTest {

    private fun tool(name: String) = AnthropicTool(
        name = name,
        description = "desc",
        inputSchema = AnthropicToolInputSchema(
            type = "object",
            properties = buildJsonObject { put("path", buildJsonObject { put("type", "string") }) },
            required = listOf("path")
        )
    )

    @Test
    fun `only the last tool gets a cache breakpoint, system always does`() {
        val request = buildCachedAgentRequest(
            model = "claude-sonnet-4-5",
            maxTokens = 1024,
            system = "you are an assistant",
            tools = listOf(tool("a"), tool("b"), tool("c")),
            messages = emptyList()
        )

        assertEquals(1, request.system.size)
        assertEquals("you are an assistant", request.system.single().text)
        assertEquals("ephemeral", request.system.single().cacheControl?.type)

        assertNull(request.tools[0].cacheControl)
        assertNull(request.tools[1].cacheControl)
        assertNotNull(request.tools[2].cacheControl)
        assertEquals("ephemeral", request.tools[2].cacheControl?.type)
    }

    @Test
    fun `empty tools list does not crash`() {
        val request = buildCachedAgentRequest("m", 100, "sys", emptyList(), emptyList())
        assertTrue(request.tools.isEmpty())
        assertEquals("ephemeral", request.system.single().cacheControl?.type)
    }

    // Регрессия на баг из Day 34 (AnthropicToolInputSchema.type пропадал из JSON из-за
    // encodeDefaults=false на shared HttpClient). Тот же Json-конфиг здесь — проверяем, что
    // ни system.type, ни cache_control.type не страдают от того же landmine.
    @Test
    fun `serialized JSON matches Anthropic cache_control wire shape under encodeDefaults=false`() {
        val request = buildCachedAgentRequest(
            model = "claude-sonnet-4-5",
            maxTokens = 1024,
            system = "sys prompt",
            tools = listOf(tool("read_file")),
            messages = emptyList()
        )
        val json = Json { encodeDefaults = false }.encodeToString(AnthropicAgentRequest.serializer(), request)

        assertTrue(json.contains("\"system\":["), "system must serialize as a block list, not a plain string")
        assertTrue(json.contains("\"type\":\"text\""), "system block type must not be dropped")
        assertTrue(
            json.contains("\"cache_control\":{\"type\":\"ephemeral\"}"),
            "cache_control must be present with the ephemeral type"
        )
        // ровно 2 брейкпоинта ожидаются (system + последний tool) — в пределах лимита Anthropic в 4
        val breakpoints = Regex("\"cache_control\"").findAll(json).count()
        assertEquals(2, breakpoints)
    }
}
