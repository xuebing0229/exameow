<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useI18nStore } from '@/stores/i18n'
import { usePracticeStore } from '@/stores/practice'
import { useConfigStore } from '@/stores/config'
import { api } from '@/api'
import { isCloudflare } from '@/utils/platform'
import { searchQuestions, type MatchScope, type SearchHit } from '@/utils/questionSearch'
import { useSearchSettings, getSearchSettings } from '@/composables/useSearchSettings'
import type { AnswerResult, QuestionType } from '@exameow/shared'
import {
  MagnifyingGlassIcon,
  AdjustmentsHorizontalIcon,
  SparklesIcon,
  ChevronDownIcon,
} from '@heroicons/vue/24/outline'
import SearchHitCard from '@/components/search/SearchHitCard.vue'
import SearchSettingsPanel from '@/components/search/SearchSettingsPanel.vue'

const query = defineModel<string>('query', { default: '' })
const props = withDefaults(defineProps<{
  queryPlacement?: 'top' | 'bottom'
  collapsibleQuery?: boolean
  showQuerySettings?: boolean
}>(), {
  queryPlacement: 'top',
  collapsibleQuery: false,
  showQuerySettings: true,
})

const router = useRouter()
const i18n = useI18nStore()
const practiceStore = usePracticeStore()
const configStore = useConfigStore()

const ALL_TYPES = ['single_choice', 'multi_choice', 'true_false', 'fill_blank', 'short_answer'] as QuestionType[]

const { selectedBankIds, scope, selectedTypes } = useSearchSettings()
const showSettings = ref(false)

// ---------- search ----------
const hits = ref<SearchHit[]>([])
const searched = ref(false)
let debounceTimer: ReturnType<typeof setTimeout> | null = null

const queryInput = ref<HTMLTextAreaElement | null>(null)
const QUERY_MAX_HEIGHT = 280
const queryExpanded = ref(!props.collapsibleQuery)

function toggleQuery() {
  queryExpanded.value = !queryExpanded.value
  if (queryExpanded.value) nextTick(autoGrow)
}

function autoGrow() {
  const el = queryInput.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, QUERY_MAX_HEIGHT)}px`
}

watch(query, () => nextTick(autoGrow))

function runSearch() {
  hits.value = searchQuestions(query.value, practiceStore.banks, {
    bankIds: selectedBankIds.value,
    scope: scope.value,
    types: selectedTypes.value,
  })
  searched.value = query.value.trim().length > 0
}

watch(
  [query, selectedBankIds, scope, selectedTypes, () => practiceStore.banks],
  () => {
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(runSearch, 200)
  },
  { deep: true, immediate: true },
)

const exactHits = computed(() => hits.value.filter((h) => h.tier === 'exact'))
const fuzzyHits = computed(() => hits.value.filter((h) => h.tier === 'fuzzy'))

// ---------- AI answer ----------
const aiLoading = ref(false)
const aiNotConfigured = ref(false)
const aiError = ref('')
const aiResult = ref<AnswerResult | null>(null)
let abortController: AbortController | null = null

watch(query, () => {
  aiResult.value = null
  aiError.value = ''
  aiNotConfigured.value = false
})

async function askAI() {
  const q = query.value.trim()
  if (!q || aiLoading.value) return
  if (!configStore.configured) {
    aiNotConfigured.value = true
    aiError.value = i18n.t('searchNotConfigured')
    return
  }
  aiLoading.value = true
  aiNotConfigured.value = false
  aiError.value = ''
  aiResult.value = null
  abortController = new AbortController()
  const language = i18n.locale === 'zh' ? 'Chinese' : 'English'
  try {
    const config = configStore.getConfig()
    if (isCloudflare() && configStore.aiProvider === 'custom') {
      const { answerViaCustomAI } = await import('@/utils/answerClient')
      aiResult.value = await answerViaCustomAI(q, language, config, abortController.signal)
    } else {
      aiResult.value = await api.answerQuestion(q, language, config, abortController.signal)
    }
  } catch (e: any) {
    if (e?.name !== 'AbortError') aiError.value = e?.message || String(e)
  } finally {
    aiLoading.value = false
    abortController = null
  }
}

function cancelAI() {
  abortController?.abort()
  aiLoading.value = false
}

onMounted(() => {
  if (!configStore.configured) configStore.loadSaved()
  nextTick(autoGrow)
})

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
  abortController?.abort()
})

const hasBanks = computed(() => practiceStore.banks.length > 0)
</script>

<template>
  <div class="flex flex-col">
    <!-- Search input -->
    <div
      class="card-filled p-4"
      :class="props.queryPlacement === 'bottom' ? 'order-last mt-4' : 'mb-4'"
    >
      <button
        v-if="props.collapsibleQuery"
        type="button"
        class="w-full flex items-center gap-3 text-left"
        @click="toggleQuery"
      >
        <MagnifyingGlassIcon class="w-5 h-5 shrink-0" style="color: rgb(var(--md-on-surface-variant))" />
        <span class="text-title-md flex-1">OCR 识别内容</span>
        <span class="text-body-sm" style="color: rgb(var(--md-on-surface-variant))">
          {{ query.trim().length }} 字
        </span>
        <ChevronDownIcon
          class="w-5 h-5 transition-transform"
          :class="queryExpanded ? 'rotate-180' : ''"
          style="color: rgb(var(--md-on-surface-variant))"
        />
      </button>

      <div v-show="!props.collapsibleQuery || queryExpanded" class="flex items-start gap-2" :class="props.collapsibleQuery ? 'mt-3' : ''">
        <MagnifyingGlassIcon class="w-5 h-5 mt-2 shrink-0" style="color: rgb(var(--md-on-surface-variant))" />
        <textarea
          ref="queryInput"
          v-model="query"
          rows="2"
          class="flex-1 bg-transparent resize-none outline-none text-body-lg"
          :placeholder="i18n.t('searchInputPlaceholder')"
          style="color: rgb(var(--md-on-surface)); overflow-y: auto"
        />
        <button
          v-if="props.showQuerySettings"
          class="btn-tonal text-sm shrink-0 !px-3 !py-2"
          :style="{ color: showSettings ? 'rgb(var(--md-primary))' : 'rgb(var(--md-on-surface-variant))' }"
          @click="showSettings = !showSettings"
          :title="i18n.t('searchSettings')"
        >
          <AdjustmentsHorizontalIcon class="w-4 h-4" />
          <span class="hidden sm:inline">{{ i18n.t('searchSettings') }}</span>
        </button>
      </div>

      <!-- Settings panel -->
      <div v-if="props.showQuerySettings && showSettings" class="mt-4 pt-4" style="border-top: 1px solid rgb(var(--md-outline-variant) / 0.4)">
        <SearchSettingsPanel />
      </div>
    </div>

    <!-- AI row -->
    <div class="flex items-center justify-between mb-4">
      <span v-if="searched" class="text-body-md" style="color: rgb(var(--md-on-surface-variant))">
        {{ i18n.t('searchResultCount', { count: hits.length }) }}
      </span>
      <span v-else />
      <button class="btn-tonal text-sm flex items-center gap-1.5" :disabled="!query.trim() || aiLoading" @click="askAI">
        <SparklesIcon class="w-4 h-4" />
        {{ i18n.t('searchAskAI') }}
      </button>
    </div>

    <!-- AI card -->
    <div
      v-if="aiLoading || aiError || aiResult"
      class="card-filled p-4 sm:p-5 mb-4 border"
      :style="{ borderColor: 'rgb(var(--md-primary) / 0.4)' }"
    >
      <div class="flex items-center gap-2 mb-2">
        <SparklesIcon class="w-5 h-5" style="color: rgb(var(--md-primary))" />
        <span class="text-title-md" style="color: rgb(var(--md-primary))">{{ i18n.t('searchAskAI') }}</span>
      </div>

      <div v-if="aiLoading" class="flex items-center justify-between">
        <span class="text-body-md" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchAiThinking') }}</span>
        <button class="btn-outlined !h-8 text-xs !px-3" @click="cancelAI">{{ i18n.t('searchCancel') }}</button>
      </div>

      <div v-else-if="aiError">
        <p class="text-body-md mb-3" :style="{ color: 'rgb(var(--md-error))' }">{{ aiError }}</p>
        <div class="flex gap-2">
          <button class="btn-tonal !h-8 text-xs !px-3" @click="askAI">{{ i18n.t('searchRetry') }}</button>
          <button
            v-if="aiNotConfigured"
            class="btn-outlined !h-8 text-xs !px-3"
            @click="router.push('/mine/config')"
          >
            {{ i18n.t('searchGotoConfig') }}
          </button>
        </div>
      </div>

      <div v-else-if="aiResult" class="space-y-3">
        <div>
          <div class="text-label-lg mb-1" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('tableAnswer') }}</div>
          <p class="text-body-lg whitespace-pre-wrap">{{ aiResult.answer }}</p>
        </div>
        <div>
          <div class="text-label-lg mb-1" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('tableAnalysis') }}</div>
          <p class="text-body-md whitespace-pre-wrap" style="color: rgb(var(--md-on-surface-variant))">{{ aiResult.analysis }}</p>
        </div>
      </div>
    </div>

    <!-- Empty states -->
    <div v-if="!hasBanks" class="card-outlined p-8 text-center">
      <p class="text-body-lg" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchNoBanks') }}</p>
    </div>
    <div v-else-if="searched && hits.length === 0" class="card-outlined p-8 text-center">
      <h3 class="text-title-lg mb-1">{{ i18n.t('searchNoResults') }}</h3>
      <p class="text-body-md" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchNoResultsHint') }}</p>
    </div>

    <!-- Results -->
    <div v-if="exactHits.length" class="mb-4">
      <div class="flex items-center gap-2 mb-2">
        <span class="text-title-md">{{ i18n.t('searchExactMatches') }}</span>
        <span
          class="text-[11px] font-medium px-2 py-0.5 rounded-full tabular-nums"
          :style="{ backgroundColor: 'rgb(var(--md-primary-container))', color: 'rgb(var(--md-on-primary-container))' }"
        >
          {{ exactHits.length }}
        </span>
      </div>
      <div class="space-y-3">
        <SearchHitCard v-for="(hit, idx) in exactHits" :key="`e-${hit.bankId}-${hit.question.id}-${idx}`" :hit="hit" />
      </div>
    </div>

    <div v-if="fuzzyHits.length">
      <div class="flex items-center gap-2 mb-2">
        <span class="text-title-md" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchFuzzyMatches') }}</span>
        <span
          class="text-[11px] font-medium px-2 py-0.5 rounded-full tabular-nums"
          :style="{ backgroundColor: 'rgb(var(--md-surface-container-highest))', color: 'rgb(var(--md-on-surface-variant))' }"
        >
          {{ fuzzyHits.length }}
        </span>
      </div>
      <div class="space-y-3">
        <SearchHitCard v-for="(hit, idx) in fuzzyHits" :key="`f-${hit.bankId}-${hit.question.id}-${idx}`" :hit="hit" />
      </div>
    </div>
  </div>
</template>
