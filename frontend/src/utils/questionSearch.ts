import type { Question, QuestionBank, QuestionType } from '@exameow/shared'

export type MatchScope = 'stem' | 'stem_options' | 'options'
export type MatchTier = 'exact' | 'fuzzy'

export interface SearchSettings {
  bankIds: string[] | null
  scope: MatchScope
  types: QuestionType[] | null
  mode?: 'search' | 'scan'
}

export interface SearchHit {
  question: Question
  bankId: string
  bankName: string
  score: number
  tier: MatchTier
}

const FUZZY_MIN_SCORE = 0.3
const SCAN_MIN_SCORE = 0.45
const EXACT_CONTAIN_MIN_LEN = 6
const MAX_HITS = 50
const BM25_K1 = 1.5
const BM25_B = 0.75

export function normalizeText(s: string): string {
  return s
    .replace(/[\u2E80-\u2EFF\u2F00-\u2FDF\uF900-\uFAFF]/g, (ch) => ch.normalize('NFKC'))
    .toLowerCase()
    .replace(/[！-～]/g, (ch) => String.fromCharCode(ch.charCodeAt(0) - 0xfee0))
    .replace(/[^\p{L}\p{N}]/gu, '')
}

function bigrams(s: string): Map<string, number> {
  const map = new Map<string, number>()
  if (s.length === 1) {
    map.set(s, 1)
    return map
  }
  for (let i = 0; i < s.length - 1; i++) {
    const g = s.slice(i, i + 2)
    map.set(g, (map.get(g) || 0) + 1)
  }
  return map
}

export function similarity(query: string, target: string): number {
  if (!query || !target) return 0
  if (query === target) return 1

  const qGrams = bigrams(query)
  const tGrams = bigrams(target)

  let overlap = 0
  let qTotal = 0
  for (const [g, n] of qGrams) {
    qTotal += n
    const m = tGrams.get(g)
    if (m) overlap += Math.min(n, m)
  }
  let tTotal = 0
  for (const n of tGrams.values()) tTotal += n

  const dice = qTotal + tTotal > 0 ? (2 * overlap) / (qTotal + tTotal) : 0

  if (target.includes(query)) {
    return Math.min(1, Math.max(dice, 0.6 + (0.4 * query.length) / target.length))
  }
  return dice
}

export function isExactMatch(query: string, target: string): boolean {
  if (!query || !target) return false
  if (query === target) return true
  if (query.length >= EXACT_CONTAIN_MIN_LEN && target.includes(query)) return true
  if (target.length >= EXACT_CONTAIN_MIN_LEN && query.includes(target)) return true
  return false
}

interface DocEntry {
  question: Question
  bankId: string
  bankName: string
  stemNorm: string
  optionNorms: string[]
  stemTermFreq: Map<string, number>
  termFreq: Map<string, number>
  len: number
}

interface CorpusIndex {
  docs: DocEntry[]
  df: Map<string, number>
  avgdl: number
  total: number
}

let indexCache: {
  banks: QuestionBank[]
  key: string
  index: CorpusIndex
} | null = null

function buildIndex(banks: QuestionBank[], settings: SearchSettings): CorpusIndex {
  const docs: DocEntry[] = []
  const df = new Map<string, number>()
  let totalLen = 0

  for (const bank of banks) {
    if (settings.bankIds && !settings.bankIds.includes(bank.id)) continue
    for (const question of bank.questions) {
      if (settings.types && !settings.types.includes(question.type)) continue

      const stemNorm = settings.scope === 'options' ? '' : normalizeText(question.stem)
      const optionNorms = settings.scope === 'stem'
        ? []
        : question.options.map(normalizeText)
      const docText = stemNorm + optionNorms.join('')
      const stemTermFreq = bigrams(stemNorm)
      const termFreq = bigrams(docText)
      let len = 0
      for (const n of termFreq.values()) len += n
      totalLen += len

      for (const term of termFreq.keys()) {
        df.set(term, (df.get(term) || 0) + 1)
      }

      docs.push({ question, bankId: bank.id, bankName: bank.name, stemNorm, optionNorms, stemTermFreq, termFreq, len })
    }
  }

  return {
    docs,
    df,
    avgdl: docs.length > 0 ? totalLen / docs.length : 0,
    total: docs.length,
  }
}

function getIndex(banks: QuestionBank[], settings: SearchSettings): CorpusIndex {
  let questionTotal = 0
  for (const b of banks) questionTotal += b.questions.length
  const bankNames = JSON.stringify(banks.map(bank => bank.name))
  const key = `${settings.scope}|${settings.bankIds?.join(',') ?? ''}|${settings.types?.join(',') ?? ''}|${banks.length}:${questionTotal}|${bankNames}`
  if (indexCache && indexCache.banks === banks && indexCache.key === key) {
    return indexCache.index
  }
  const index = buildIndex(banks, settings)
  indexCache = { banks, key, index }
  return index
}

export interface ScanDecision {
  action: 'set' | 'clear' | 'keep'
  hit?: SearchHit
  reason: string
}

const SCAN_SWITCH_MARGIN = 0.05
const SCAN_GONE_SCORE = 0.35

export function decideScanResult(prev: Question | null, hits: SearchHit[]): ScanDecision {
  if (hits.length === 0) return { action: 'clear', reason: 'no candidates' }
  const best = hits[0]!
  if (!prev) return { action: 'set', hit: best, reason: 'first hit' }

  const same = (q: Question) => q === prev || (q.id !== '' && q.id === prev.id)
  if (same(best.question)) return { action: 'set', hit: best, reason: 'same question, refresh score' }

  if (best.tier === 'exact') return { action: 'set', hit: best, reason: 'exact hit, switch' }

  const prevScore = hits.find((h) => same(h.question))?.score ?? 0
  if (prevScore < SCAN_GONE_SCORE) {
    return { action: 'set', hit: best, reason: `prev gone (${prevScore.toFixed(2)}), switch` }
  }
  if (best.score >= prevScore + SCAN_SWITCH_MARGIN) {
    return { action: 'set', hit: best, reason: `better hit (${best.score.toFixed(2)} >= ${prevScore.toFixed(2)}+${SCAN_SWITCH_MARGIN})` }
  }
  return { action: 'keep', reason: `hysteresis keep (${prevScore.toFixed(2)} vs ${best.score.toFixed(2)})` }
}

export function searchQuestions(
  query: string,
  banks: QuestionBank[],
  settings: SearchSettings,
): SearchHit[] {
  const q = normalizeText(query)
  if (!q) return []

  const mode = settings.mode ?? 'search'
  const index = getIndex(banks, settings)
  if (index.total === 0) return []

  const qTerms = bigrams(q)
  const idfCache = new Map<string, number>()
  const idfOf = (term: string): number => {
    let v = idfCache.get(term)
    if (v === undefined) {
      const n = index.df.get(term) || 0
      v = Math.log(1 + (index.total - n + 0.5) / (n + 0.5))
      idfCache.set(term, v)
    }
    return v
  }

  const hits: SearchHit[] = []
  const minScore = mode === 'scan' ? SCAN_MIN_SCORE : FUZZY_MIN_SCORE

  if (mode === 'scan') {
    const coverageScore = (terms: Map<string, number>, queryTerms: Map<string, number>): number => {
      let mass = 0
      let covered = 0
      for (const [term, f] of terms) {
        const w = idfOf(term) * ((f * (BM25_K1 + 1)) / (f + BM25_K1))
        mass += w
        if (queryTerms.has(term)) covered += w
      }
      return mass > 0 ? Math.min(1, covered / mass) : 0
    }

    for (const doc of index.docs) {
      let tier: MatchTier = 'fuzzy'
      if (isExactMatch(q, doc.stemNorm) || doc.optionNorms.some((o) => isExactMatch(q, o))) {
        tier = 'exact'
      }

      // A crop often contains only the stem. Scoring solely against stem + all
      // options unfairly dilutes a correct stem match, so keep the stronger view.
      const score = Math.max(
        coverageScore(doc.stemTermFreq, qTerms),
        coverageScore(doc.termFreq, qTerms),
      )

      if (tier === 'exact' || score >= minScore) {
        hits.push({ question: doc.question, bankId: doc.bankId, bankName: doc.bankName, score, tier })
      }
    }
  } else {
    let denom = 0
    for (const term of qTerms.keys()) {
      if (!index.df.has(term)) continue
      denom += idfOf(term)
    }

    for (const doc of index.docs) {
      let tier: MatchTier = 'fuzzy'
      if (isExactMatch(q, doc.stemNorm) || doc.optionNorms.some((o) => isExactMatch(q, o))) {
        tier = 'exact'
      }

      let score = 0
      if (denom > 0) {
        let num = 0
        const lenNorm = BM25_K1 * (1 - BM25_B + (BM25_B * doc.len) / (index.avgdl || 1))
        for (const [term] of qTerms) {
          const f = doc.termFreq.get(term)
          if (!f) continue
          num += idfOf(term) * ((f * (BM25_K1 + 1)) / (f + lenNorm))
        }
        score = Math.min(1, num / denom)
      }

      if (tier === 'exact' || score >= minScore) {
        hits.push({ question: doc.question, bankId: doc.bankId, bankName: doc.bankName, score, tier })
      }
    }
  }

  hits.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score
    return a.tier === b.tier ? 0 : a.tier === 'exact' ? -1 : 1
  })
  return hits.slice(0, MAX_HITS)
}
