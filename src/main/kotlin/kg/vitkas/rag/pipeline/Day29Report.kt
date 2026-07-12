package kg.vitkas.rag.pipeline

import kg.vitkas.rag.model.ExperimentRun
import kg.vitkas.rag.model.ExperimentSummary
import kg.vitkas.rag.model.OllamaChatOptions
import kg.vitkas.rag.model.RagError
import kg.vitkas.rag.server.routes.DAY24_SYSTEM_PROMPT
import kg.vitkas.rag.server.routes.LOCAL_OPTIMIZED_SYSTEM_PROMPT
import kg.vitkas.rag.server.routes.LOCAL_SOFT_QUOTE_SYSTEM_PROMPT
import kg.vitkas.rag.server.routes.runDay24Pipeline
import kg.vitkas.rag.server.routes.verifyCitations
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("kg.vitkas.rag.pipeline.Day29Report")

// qwen2.5-coder:7b был удалён с сервера (ollama rm) — сравнение "модель" убрано, раз сравнивать
// больше не с чем (ollama list сейчас: qwen2.5:7b-instruct, qwen2.5:7b-instruct-q4_0,
// nomic-embed-text:latest). "Модель" и "Квантование" по факту были одним и тем же экспериментом
// (два доступных варианта одной instruct-модели) — объединены в одну секцию отчёта.
private const val MODEL_INSTRUCT_Q4_K_M = "qwen2.5:7b-instruct"       // дефолтный квант при pull без суффикса
private const val MODEL_INSTRUCT_Q4_0 = "qwen2.5:7b-instruct-q4_0"

private const val TEMPERATURE_LOW = 0.1
private const val TEMPERATURE_HIGH = 0.5

// Ретрив-параметры зеркалят дефолты AskRerankedRequest/DebugRoutes — тот же retrieval, что у /ask-local.
private const val REPORT_TOP_K = 8
private const val REPORT_THRESHOLD = 0.55f

private fun buildCompleteGeneration(
    ollamaGenerationClient: OllamaGenerationClient,
    modelOverride: String? = null,
    temperature: Double? = null
): (suspend (String, String, Int) -> Result<String>)? {
    if (modelOverride == null && temperature == null) return null
    val options = temperature?.let { OllamaChatOptions(temperature = it) }
    return { system, userMessage, maxTokens ->
        ollamaGenerationClient.complete(system, userMessage, maxTokens, options, modelOverride)
    }
}

private suspend fun runScenario(
    label: String,
    runs: Int,
    question: String,
    topK: Int,
    threshold: Float,
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    complete: suspend (String, String, Int) -> Result<String>,
    mapError: (String, Throwable) -> RagError,
    generationSystemPrompt: String,
    completeGeneration: (suspend (String, String, Int) -> Result<String>)?
): ExperimentSummary {
    val runResults = mutableListOf<ExperimentRun>()
    var firstErrorMessage: String? = null

    repeat(runs) { i ->
        val start = System.currentTimeMillis()
        val run = runCatching {
            val result = runDay24Pipeline(
                question = question,
                topK = topK,
                threshold = threshold,
                embeddingService = embeddingService,
                repo = repo,
                complete = complete,
                mapError = mapError,
                generationSystemPrompt = generationSystemPrompt,
                completeGeneration = completeGeneration
            )
            val elapsedMs = System.currentTimeMillis() - start
            val mode = if (result.parsed.isDontKnow) "no_context" else "rag_with_citations"
            val citations = if (result.parsed.isDontKnow) emptyList() else verifyCitations(result.parsed.citations, result.filtered)
            ExperimentRun(citations = citations.size, mode = mode, elapsedMs = elapsedMs)
        }.getOrElse { e ->
            val elapsedMs = System.currentTimeMillis() - start
            logger.warn("🔵 RAG_DAY29_REPORT [{}] run {}/{} упал: {}", label, i + 1, runs, e.message)
            if (firstErrorMessage == null) firstErrorMessage = e.message ?: e.toString()
            ExperimentRun(citations = 0, mode = "error", elapsedMs = elapsedMs)
        }

        logger.info(
            "🔵 RAG_DAY29_REPORT [{}] run {}/{}: citations={} mode={} ms={}",
            label, i + 1, runs, run.citations, run.mode, run.elapsedMs
        )
        runResults.add(run)
    }

    val errorRunsCount = runResults.count { it.mode == "error" }
    val avgMs = if (runResults.isNotEmpty()) runResults.sumOf { it.elapsedMs } / runResults.size else 0L

    return when {
        // Все прогоны упали по exception (например модель не найдена) — цифра успеха недействительна,
        // не путать с "модель честно не справилась и дала 0 цитат".
        errorRunsCount == runs -> ExperimentSummary(
            label = label,
            successCount = -1,
            totalRuns = runs,
            avgMs = avgMs,
            errorMessage = firstErrorMessage
        )
        // Часть прогонов упала, часть отработала — successCount по-прежнему честный (упавшие прогоны
        // и так не наберут citations>=2), но errorMessage всплывает, чтобы не потерять причину.
        errorRunsCount > 0 -> ExperimentSummary(
            label = label,
            successCount = runResults.count { it.citations >= 2 },
            totalRuns = runs,
            avgMs = avgMs,
            errorMessage = "$errorRunsCount/$runs прогонов упали с ошибкой (первая): $firstErrorMessage"
        )
        else -> ExperimentSummary(
            label = label,
            successCount = runResults.count { it.citations >= 2 },
            totalRuns = runs,
            avgMs = avgMs
        )
    }
}

private fun summaryRow(s: ExperimentSummary): String {
    val successCell = if (s.successCount < 0) "⚠️ ОШИБКА" else "${s.successCount}/${s.totalRuns}"
    return "| ${s.label} | $successCell | ${s.avgMs} мс |"
}

private const val TABLE_HEADER = "| Сценарий | Успех (citations≥2) | Среднее время |\n|---|---|---|"

// Печатает таблицу сценариев секции + под ней явные ⚠️-заметки для тех, у кого errorMessage != null —
// нулевой success rate из-за отсутствующей модели не должен визуально совпадать с нулём "модель
// честно не справилась".
private fun StringBuilder.appendSection(title: String, scenarios: List<ExperimentSummary>) {
    appendLine("## $title")
    appendLine()
    appendLine(TABLE_HEADER)
    scenarios.forEach { appendLine(summaryRow(it)) }
    val errors = scenarios.mapNotNull { s -> s.errorMessage?.let { s.label to it } }
    if (errors.isNotEmpty()) {
        appendLine()
        errors.forEach { (label, msg) -> appendLine("> ⚠️ **$label**: $msg") }
    }
    appendLine()
}

private fun buildMarkdownReport(
    baseline: ExperimentSummary,
    temp01: ExperimentSummary,
    temp05: ExperimentSummary,
    quantQ4KM: ExperimentSummary,
    quantQ40: ExperimentSummary,
    promptHard: ExperimentSummary,
    promptSoft: ExperimentSummary,
    final: ExperimentSummary
): String = buildString {
    appendLine("# День 29 — отчёт по оптимизации локальной LLM")
    appendLine()
    appendLine("Сгенерировано автоматически через POST /day29-report. Метрика успеха — `citations >= 2`")
    appendLine("(минимум цитат, требуемый DAY24_SYSTEM_PROMPT), после прогона через verifyCitations().")
    appendLine("«⚠️ ОШИБКА» вместо цифры — сценарий целиком упал по exception (например модель не найдена")
    appendLine("в Ollama), а не то, что модель честно не справилась с задачей — это разные вещи.")
    appendLine()

    appendSection("Baseline", listOf(baseline))
    appendSection("Temperature", listOf(temp01, temp05))
    // "Модель" и "Квантование" объединены: qwen2.5-coder:7b удалена (ollama rm), сравнивать "модель"
    // больше не с чем — реально доступны только два кванта одной instruct-модели, это и есть
    // единственный актуальный эксперимент про выбор модели/кванта.
    appendSection("Модель / Квантование (qwen2.5:7b-instruct, q4_K_M vs q4_0)", listOf(quantQ4KM, quantQ40))
    appendSection("Prompt-шаблон", listOf(promptHard, promptSoft))
    appendSection("Финальная конфигурация", listOf(final))

    appendLine("## Вывод")
    appendLine()
    val all = listOf(baseline, temp01, temp05, quantQ4KM, quantQ40, promptHard, promptSoft, final)
    val best = all.filter { it.successCount >= 0 }.maxByOrNull { it.successCount.toDouble() / it.totalRuns }
    if (best != null) {
        appendLine("Лучший success rate по данному прогону: **${best.label}** — ${best.successCount}/${best.totalRuns}.")
    }
    val failed = all.filter { it.successCount < 0 }
    if (failed.isNotEmpty()) {
        appendLine("Сценарии, упавшие целиком (исключены из сравнения выше): ${failed.joinToString(", ") { it.label }}.")
    }
    appendLine("Итоговая конфигурация раздела «Финальная конфигурация» отражает состояние по итогам")
    appendLine("предыдущих ручных экспериментов Дня 28-30 (temperature не влияла на citations, few-shot")
    appendLine("не поднял потолок, JSON-формат ухудшил результат) — числа выше подтверждают или опровергают")
    appendLine("этот выбор на новом прогоне, а не гарантируют его оптимальность.")
}

suspend fun generateDay29Report(
    question: String,
    runsPerScenario: Int,
    embeddingService: EmbeddingService,
    repo: IndexRepository,
    ollamaGenerationClient: OllamaGenerationClient
): Pair<String, List<ExperimentSummary>> {
    val mapError = { msg: String, e: Throwable -> RagError.OllamaGenerationError(msg, e) }

    suspend fun scenario(label: String, modelOverride: String? = null, temperature: Double? = null, prompt: String = DAY24_SYSTEM_PROMPT) =
        runScenario(
            label = label,
            runs = runsPerScenario,
            question = question,
            topK = REPORT_TOP_K,
            threshold = REPORT_THRESHOLD,
            embeddingService = embeddingService,
            repo = repo,
            complete = ollamaGenerationClient::complete,
            mapError = mapError,
            generationSystemPrompt = prompt,
            completeGeneration = buildCompleteGeneration(ollamaGenerationClient, modelOverride, temperature)
        )

    val baseline      = scenario("baseline")
    val temp01        = scenario("temperature=$TEMPERATURE_LOW", temperature = TEMPERATURE_LOW)
    val temp05        = scenario("temperature=$TEMPERATURE_HIGH", temperature = TEMPERATURE_HIGH)
    val quantQ4KM      = scenario("quant=q4_K_M ($MODEL_INSTRUCT_Q4_K_M)", modelOverride = MODEL_INSTRUCT_Q4_K_M)
    val quantQ40       = scenario("quant=q4_0 ($MODEL_INSTRUCT_Q4_0)", modelOverride = MODEL_INSTRUCT_Q4_0)
    val promptHard     = scenario("prompt=LOCAL_OPTIMIZED (строгая дословность)", modelOverride = MODEL_INSTRUCT_Q4_K_M, prompt = LOCAL_OPTIMIZED_SYSTEM_PROMPT)
    val promptSoft     = scenario("prompt=LOCAL_SOFT_QUOTE (мягкая дословность)", modelOverride = MODEL_INSTRUCT_Q4_K_M, prompt = LOCAL_SOFT_QUOTE_SYSTEM_PROMPT)
    val final          = scenario("финальная конфигурация", modelOverride = MODEL_INSTRUCT_Q4_K_M, temperature = TEMPERATURE_LOW, prompt = LOCAL_OPTIMIZED_SYSTEM_PROMPT)

    val summary = listOf(baseline, temp01, temp05, quantQ4KM, quantQ40, promptHard, promptSoft, final)

    val markdown = buildMarkdownReport(
        baseline = baseline,
        temp01 = temp01,
        temp05 = temp05,
        quantQ4KM = quantQ4KM,
        quantQ40 = quantQ40,
        promptHard = promptHard,
        promptSoft = promptSoft,
        final = final
    )

    return markdown to summary
}
