import type { Question } from '@exameow/shared'
import { QuestionType } from '@exameow/shared'

export type TextImportIssueLevel = 'warning' | 'error'

export interface TextImportIssue {
  questionId?: string
  questionNumber?: number
  level: TextImportIssueLevel
  message: string
}

export interface TextImportResult {
  questions: Question[]
  issues: TextImportIssue[]
  unparsedBlocks: string[]
}

type SupportedQuestionType = Question['type']

const QUESTION_START = /^\s*(?:第\s*)?(\d{1,4})\s*[.、．:：)）]\s*(.*)$/
const OPTION_LINE = /^\s*([A-HＡ-Ｈ])\s*[.、．:：)）]\s*(.*)$/i
const ANSWER_LINE = /^\s*(?:参考答案|正确答案|答案|答)\s*[:：]\s*(.*)$/i
const ANALYSIS_LINE = /^\s*(?:答案解析|参考解析|解析|解答|说明)\s*[:：]\s*(.*)$/i
const TYPE_LINE = /^\s*(?:[一二三四五六七八九十百0-9]+\s*[、.．)）]\s*)?(?:题型\s*[:：]\s*)?(单选题?|单项选择题?|多选题?|多项选择题?|判断题?|是非题?|填空题?|简答题?|问答题?|single(?:\s*choice)?|multiple(?:\s*choice)?|true\s*\/\s*false|fill(?:\s*in\s*the\s*blank)?|short(?:\s*answer)?)\s*[:：]?\s*$/i
const ANSWER_SECTION = /^\s*(?:参考)?答案(?:及解析)?\s*[:：]?\s*$/i
const NEEDS_CONFIRMATION = /\[(?:NEEDS CONFIRMATION|待确认)\]|【待确认】/i

function normalizeText(text: string): string {
  return text
    .replace(/^\uFEFF/, '')
    .replace(/\r\n?/g, '\n')
    .replace(/\u00a0/g, ' ')
    .replace(/[Ａ-Ｈ]/g, c => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
    .replace(/[ 	]+$/gm, '')
    .replace(/\n{4,}/g, '\n\n\n')
    .trim()
}

function parseTypeLabel(label: string): SupportedQuestionType | null {
  const value = label.trim().toLowerCase().replace(/\s+/g, '')
  if (value.includes('多选') || value.includes('多项') || value.includes('multiple')) return QuestionType.MultiChoice
  if (value.includes('单选') || value.includes('单项') || value.includes('single')) return QuestionType.SingleChoice
  if (value.includes('判断') || value.includes('是非') || value.includes('true/false')) return QuestionType.TrueFalse
  if (value.includes('填空') || value.includes('fill')) return QuestionType.FillBlank
  if (value.includes('简答') || value.includes('问答') || value.includes('short')) return QuestionType.ShortAnswer
  return null
}

function isTypeHeading(line: string): SupportedQuestionType | null {
  const match = line.match(TYPE_LINE)
  return match?.[1] ? parseTypeLabel(match[1]) : null
}

function extractQuestionNumber(line: string): string | null {
  return line.match(QUESTION_START)?.[1] ?? null
}

function parseAnswerMap(lines: string[]): Map<string, string> {
  const map = new Map<string, string>()
  for (const line of lines) {
    const matches = [...line.matchAll(/(?:^|\s)(\d{1,4})\s*[.、．:：)）]\s*([A-H]{1,8}|正确|错误|对|错|√|×|True|False)(?=\s|$)/gi)]
    for (const match of matches) {
      if (match[1] && match[2]) map.set(match[1], match[2])
    }
  }
  return map
}

interface RawBlock {
  number: string
  lines: string[]
  inheritedType: SupportedQuestionType | null
}

function splitQuestionBlocks(text: string): { blocks: RawBlock[]; answerMap: Map<string, string>; leftovers: string[] } {
  const lines = text.split('\n')
  const blocks: RawBlock[] = []
  const leftovers: string[] = []
  const answerLines: string[] = []
  let current: RawBlock | null = null
  let inheritedType: SupportedQuestionType | null = null
  let inAnswerSection = false

  const flush = () => {
    if (!current) return
    current.lines = current.lines.filter((line, index, all) => line.trim() || (index > 0 && index < all.length - 1))
    blocks.push(current)
    current = null
  }

  for (const line of lines) {
    const trimmed = line.trim()
    if (ANSWER_SECTION.test(trimmed)) {
      flush()
      inAnswerSection = true
      continue
    }
    if (inAnswerSection) {
      if (parseAnswerMap([line]).size > 0) {
        answerLines.push(line)
        continue
      }
      const numbered = QUESTION_START.test(line)
      const looksLikeQuestion = numbered && !/^\s*(?:第\s*)?\d+\s*[.、．:：)）]\s*(?:[A-H]{1,8}|正确|错误|对|错|√|×|True|False)\s*$/i.test(line)
      if (!looksLikeQuestion) {
        answerLines.push(line)
        continue
      }
      inAnswerSection = false
    }

    const headingType = isTypeHeading(trimmed)
    if (headingType) {
      flush()
      inheritedType = headingType
      continue
    }

    const start = line.match(QUESTION_START)
    if (start?.[1]) {
      flush()
      current = { number: start[1], lines: [line], inheritedType }
      continue
    }

    if (current) {
      current.lines.push(line)
    } else if (trimmed) {
      leftovers.push(line)
    }
  }
  flush()

  if (blocks.length === 0) {
    const chunks = text.split(/\n\s*\n+/).map(v => v.trim()).filter(Boolean)
    chunks.forEach((chunk, index) => {
      blocks.push({ number: String(index + 1), lines: chunk.split('\n'), inheritedType: null })
    })
    leftovers.length = 0
  }

  return { blocks, answerMap: parseAnswerMap(answerLines), leftovers }
}

function normalizeChoiceAnswer(answer: string): string {
  const letters = answer.toUpperCase().match(/[A-H]/g)
  return letters ? [...new Set(letters)].join('') : answer.trim()
}

function normalizeJudgeAnswer(answer: string): string {
  const value = answer.trim().toLowerCase()
  if (['正确', '对', '是', '√', '✓', 'true', 't', 'a'].some(v => value === v)) return '正确'
  if (['错误', '错', '否', '×', '✗', 'false', 'f', 'b'].some(v => value === v)) return '错误'
  return answer.trim()
}

function inferType(options: string[], answer: string, stem: string): SupportedQuestionType {
  const normalizedAnswer = normalizeChoiceAnswer(answer)
  if (options.length >= 2) {
    return /^[A-H]{2,}$/.test(normalizedAnswer) ? QuestionType.MultiChoice : QuestionType.SingleChoice
  }
  if (/^(?:正确|错误|对|错|是|否|√|✓|×|✗|true|false|t|f)$/i.test(answer.trim())) return QuestionType.TrueFalse
  if (/_{2,}|（\s*）|\(\s*\)|【\s*】|\[\s*\]/.test(stem)) return QuestionType.FillBlank
  return QuestionType.ShortAnswer
}

function parseBlock(block: RawBlock, index: number, answerMap: Map<string, string>): Question | null {
  const lines = block.lines.map(v => v.trim()).filter(Boolean)
  if (lines.length === 0) return null

  let first = lines[0] ?? ''
  const numbered = first.match(QUESTION_START)
  if (numbered) first = numbered[2]?.trim() ?? ''

  let explicitType = block.inheritedType
  const leadingType = first.match(/^\s*[【\[(]?(单选题?|单项选择题?|多选题?|多项选择题?|判断题?|是非题?|填空题?|简答题?|问答题?)[】\])]?[.、．:：\s-]*(.*)$/i)
  if (leadingType?.[1]) {
    explicitType = parseTypeLabel(leadingType[1]) ?? explicitType
    first = leadingType[2]?.trim() ?? ''
  }

  const stemParts: string[] = first ? [first] : []
  const options: string[] = []
  const optionKeys: string[] = []
  const analysisParts: string[] = []
  let answer = ''
  let mode: 'stem' | 'option' | 'analysis' = 'stem'

  for (const line of lines.slice(1)) {
    const type = isTypeHeading(line)
    if (type) {
      explicitType = type
      continue
    }
    const option = line.match(OPTION_LINE)
    if (option?.[1]) {
      optionKeys.push(option[1].toUpperCase())
      options.push((option[2] ?? '').trim())
      mode = 'option'
      continue
    }
    const answerMatch = line.match(ANSWER_LINE)
    if (answerMatch) {
      answer = (answerMatch[1] ?? '').trim()
      mode = 'stem'
      continue
    }
    const analysisMatch = line.match(ANALYSIS_LINE)
    if (analysisMatch) {
      analysisParts.push((analysisMatch[1] ?? '').trim())
      mode = 'analysis'
      continue
    }
    if (mode === 'analysis') {
      analysisParts.push(line)
    } else if (mode === 'option' && options.length > 0) {
      options[options.length - 1] = `${options[options.length - 1]} ${line}`.trim()
    } else {
      stemParts.push(line)
    }
  }

  if (!answer) answer = answerMap.get(block.number) ?? ''
  const stem = stemParts.join('\n').trim()
  if (!stem) return null
  const qtype = explicitType ?? inferType(options, answer, stem)
  if (qtype === QuestionType.SingleChoice || qtype === QuestionType.MultiChoice) {
    answer = normalizeChoiceAnswer(answer)
  } else if (qtype === QuestionType.TrueFalse) {
    answer = normalizeJudgeAnswer(answer)
  }

  const orderedOptions = optionKeys.length === options.length
    ? optionKeys.map((key, optionIndex) => ({ key, value: options[optionIndex] ?? '' }))
      .sort((a, b) => a.key.localeCompare(b.key))
      .map(item => item.value)
    : options

  return {
    id: `text-${Date.now().toString(36)}-${index + 1}`,
    type: qtype,
    stem,
    options: orderedOptions,
    answer,
    analysis: analysisParts.join('\n').trim(),
  }
}

export function validateTextImportQuestions(questions: Question[]): TextImportIssue[] {
  const issues: TextImportIssue[] = []
  questions.forEach((question, index) => {
    const base = { questionId: question.id, questionNumber: index + 1 }
    if (!question.stem.trim()) issues.push({ ...base, level: 'error', message: '题干为空' })
    if (!question.answer.trim()) issues.push({ ...base, level: 'warning', message: '未识别到答案' })
    if (NEEDS_CONFIRMATION.test(question.answer)) issues.push({ ...base, level: 'warning', message: '答案需要人工确认' })
    if (question.type === QuestionType.SingleChoice || question.type === QuestionType.MultiChoice) {
      if (question.options.filter(Boolean).length < 2) issues.push({ ...base, level: 'error', message: '选择题缺少选项' })
      const answer = normalizeChoiceAnswer(question.answer)
      if (question.type === QuestionType.SingleChoice && /^[A-H]{2,}$/.test(answer)) {
        issues.push({ ...base, level: 'error', message: '单选题识别到多个答案' })
      }
      if (/^[A-H]+$/.test(answer)) {
        const max = question.options.length
        if ([...answer].some(letter => letter.charCodeAt(0) - 64 > max)) {
          issues.push({ ...base, level: 'warning', message: '答案超出选项范围' })
        }
      }
    }
    if (question.type === QuestionType.TrueFalse && question.answer && !/^(?:正确|错误)$/i.test(normalizeJudgeAnswer(question.answer))) {
      issues.push({ ...base, level: 'warning', message: '判断题答案不是常见的正确/错误标记' })
    }
  })
  return issues
}

export function parseTextQuestions(rawText: string): TextImportResult {
  const text = normalizeText(rawText)
  if (!text) return { questions: [], issues: [], unparsedBlocks: [] }
  const { blocks, answerMap, leftovers } = splitQuestionBlocks(text)
  const unparsedBlocks = [...leftovers]
  const questions: Question[] = []
  blocks.forEach((block, index) => {
    const question = parseBlock(block, index, answerMap)
    if (question) questions.push(question)
    else unparsedBlocks.push(block.lines.join('\n'))
  })
  const issues = validateTextImportQuestions(questions)
  if (unparsedBlocks.length > 0) {
    issues.unshift({ level: 'warning', message: `有 ${unparsedBlocks.length} 段文本未能归入题目，请核对原文` })
  }
  return { questions, issues, unparsedBlocks }
}
