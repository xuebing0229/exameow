import type { Ai } from '@cloudflare/workers-types'
import { aiChat } from './ai'
import type { Question, TextImportRefactorResult } from './types'

const SYSTEM_PROMPT = `You clean imported exam-bank text for a deterministic local parser. You do not solve questions or invent missing content.

Return ONLY JSON with this shape:
{"cleaned_text":"...","notes":["..."]}

Rules:
1. Preserve every question, its wording, options, supplied answer, analysis, section title, and order.
2. Put each question in a separate block using: number + stem, A-H options, Answer:, Analysis:.
3. Normalize obvious layout noise, broken option lines, full-width punctuation, and answer-section formatting.
4. Keep single choice, multiple choice, true/false, fill blank, and short answer distinct when the source provides enough evidence.
5. Never infer or solve an answer. If an answer cannot be matched reliably, write Answer: [NEEDS CONFIRMATION].
6. Do not remove a suspicious fragment merely because it is hard to parse. Keep it and mention it in notes.
7. cleaned_text must be plain text, not Markdown fences. Notes must be short and only mention uncertainties or material repairs.`

function parseResult(raw: string): TextImportRefactorResult {
  let cleaned = raw.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '')
  const start = cleaned.indexOf('{')
  const end = cleaned.lastIndexOf('}')
  if (start >= 0 && end >= start) cleaned = cleaned.slice(start, end + 1)
  const parsed = JSON.parse(cleaned)
  if (typeof parsed?.cleaned_text !== 'string' || !parsed.cleaned_text.trim()) {
    throw new Error('AI returned empty cleaned text')
  }
  return {
    cleaned_text: parsed.cleaned_text,
    notes: Array.isArray(parsed.notes) ? parsed.notes.filter((note: unknown) => typeof note === 'string') : [],
  }
}

export async function refactorTextImport(
  ai: Ai,
  rawText: string,
  currentQuestions: Question[],
  warnings: string[],
  model?: string,
): Promise<TextImportRefactorResult> {
  const userPrompt = `RAW TEXT:\n${rawText}\n\nCURRENT LOCAL PARSE:\n${JSON.stringify(currentQuestions)}\n\nLOCAL WARNINGS:\n${warnings.length ? warnings.join('\n') : '(none)'}`
  const response = await aiChat(ai, { model, systemPrompt: SYSTEM_PROMPT, userPrompt })
  return parseResult(response)
}
