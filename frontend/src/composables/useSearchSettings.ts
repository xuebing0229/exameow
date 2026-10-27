import { ref, watch } from 'vue'
import type { MatchScope } from '@/utils/questionSearch'
import type { QuestionType } from '@exameow/shared'

const SETTINGS_KEY = 'exameow-search-settings'

export interface SearchSettings {
  bankIds: string[] | null
  scope: MatchScope
  types: QuestionType[] | null
}

function loadSettings(): SearchSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY)
    if (raw) return { bankIds: null, scope: 'stem_options', types: null, ...JSON.parse(raw) }
  } catch {}
  return { bankIds: null, scope: 'stem_options', types: null }
}

let _cached: { bankIds: string[] | null; scope: MatchScope; types: QuestionType[] | null } | null = null

export function getSearchSettings(): SearchSettings {
  if (!_cached) _cached = loadSettings()
  return { ..._cached }
}

export function useSearchSettings() {
  const stored = loadSettings()

  const selectedBankIds = ref<string[] | null>(stored.bankIds)
  const scope = ref<MatchScope>(stored.scope === 'stem' || stored.scope === 'options' ? stored.scope : 'stem_options')
  const selectedTypes = ref<QuestionType[] | null>(stored.types)

  watch(
    [selectedBankIds, scope, selectedTypes],
    () => {
      const v = { bankIds: selectedBankIds.value, scope: scope.value, types: selectedTypes.value }
      try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(v)) } catch {}
      _cached = v
    },
    { deep: true },
  )

  function toggleBank(id: string) {
    if (selectedBankIds.value === null) {
      selectedBankIds.value = [id]
      return
    }
    const idx = selectedBankIds.value.indexOf(id)
    if (idx >= 0) {
      selectedBankIds.value.splice(idx, 1)
      if (selectedBankIds.value.length === 0) selectedBankIds.value = null
    } else {
      selectedBankIds.value.push(id)
    }
  }

  function toggleType(t: QuestionType) {
    if (selectedTypes.value === null) {
      selectedTypes.value = [t]
      return
    }
    const idx = selectedTypes.value.indexOf(t)
    if (idx >= 0) {
      selectedTypes.value.splice(idx, 1)
      if (selectedTypes.value.length === 0) selectedTypes.value = null
    } else {
      selectedTypes.value.push(t)
    }
  }

  return { selectedBankIds, scope, selectedTypes, toggleBank, toggleType }
}
