---
name: course-notes
description: >
  Index of AI Advent Challenge #8 course notes (video lesson summaries) that
  back this project's context — domain (Сюцай/SyutsaiMentorPro), RAG theory
  (embeddings, chunking, vector search), and course topics (prompting, agent
  tools, memory/state, MCP). Read the referenced files on demand instead of
  guessing — don't paraphrase from training data when a lesson file answers
  the question.
  Trigger: generating or reviewing RAG pipeline code, chunking/embedding
  strategy questions, "что говорилось в уроке про X", Сюцай domain questions,
  prompting/agent-tools/memory/MCP course concepts, "почему мы сделали X"
  design-rationale questions for this course project.
---

Pointers only — files live outside the repo at
`/Users/vikim/Downloads/android/courses/ai advent 8/`. Read the specific file
when a trigger matches; don't load all of them speculatively.

## Domain context (read first for Сюцай/project-identity questions)

- `week_3/syutsai_context.md` — who the user is (Виктор Ким, Android dev,
  O! Mobile Бишкек), the course (AI Advent Challenge #8, Алексей Гладков),
  and the **SyutsaiMentorPro** KMM sibling project (Ktor Client, SQLDelight,
  MVI, targets Android/Desktop/iOS) — the domain this RAG server's knowledge
  base (Сюцай methodology, Жанат Кожамжаров) comes from.

## RAG-specific (most relevant to this repo's pipeline code)

- `week_5/2026_06_29_RAG_Эмбеддинги,_векторный_поиск_и_чанкинг.md` — RAG
  theory: what RAG is vs MCP, problems without RAG (hallucination, stale
  data, false positives), embeddings/vector search/chunking fundamentals.
  Consult when touching `EmbeddingService`, `IndexRepository`, chunking
  strategy, or explaining *why* the pipeline is shaped this way.

## Other course weeks (less directly relevant, still available)

- `week_1/01_ai_prompting_summary.md` + `01_ai_prompting_detailed_notes.md`
  — prompt engineering fundamentals.
- `week_2/02_agent_tools_summary.md` + `02_agent_tools_detailed_notes.md`
  — agent/tool-use patterns.
- `week_3/03-memory-state-summary.md` + `03-memory-state-notes.md` —
  agent memory/state design.
- `week_4/04-mcp-summary.md` + `04-mcp-notes.md` — MCP concepts;
  `week_4/mcp-day16/README.md`, `week_4/mcp-day17-server/README.md` — MCP
  server code examples from the course.

## Usage note

These are the user's own lesson notes, not this repo's source of truth —
if a note conflicts with the current code (`CLAUDE.md`, actual pipeline
implementation), the code wins; treat notes as background/rationale only.
