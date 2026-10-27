<script setup lang="ts">
import { computed } from 'vue'
import { useI18nStore } from '@/stores/i18n'
import { usePracticeStore } from '@/stores/practice'
import { useSearchSettings } from '@/composables/useSearchSettings'
import BaseSelect from '@/components/common/BaseSelect.vue'
import BaseMultiSelect from '@/components/common/BaseMultiSelect.vue'
import type { QuestionType } from '@exameow/shared'

const ALL_TYPES = ['single_choice', 'multi_choice', 'true_false', 'fill_blank', 'short_answer'] as QuestionType[]

const typeLabelKeys: Record<string, 'typeSingle' | 'typeMulti' | 'typeTrueFalse' | 'typeFillBlank' | 'typeShortAnswer'> = {
  single_choice: 'typeSingle',
  multi_choice: 'typeMulti',
  true_false: 'typeTrueFalse',
  fill_blank: 'typeFillBlank',
  short_answer: 'typeShortAnswer',
}

const i18n = useI18nStore()
const practiceStore = usePracticeStore()
const { selectedBankIds, scope, selectedTypes } = useSearchSettings()

const bankOptions = computed(() =>
  practiceStore.banks.map((b) => ({ value: b.id, label: b.name, hint: String(b.questions.length) })),
)

const bankValue = computed<string[]>({
  get: () => selectedBankIds.value ?? practiceStore.banks.map((b) => b.id),
  set: (v) => {
    selectedBankIds.value = v.length >= practiceStore.banks.length ? null : v
  },
})

const scopeOptions = computed(() => [
  { value: 'stem_options', label: i18n.t('searchMatchStemOptions') },
  { value: 'stem', label: i18n.t('searchMatchStem') },
  { value: 'options', label: i18n.t('searchMatchOptions') },
])

const typeOptions = computed(() =>
  ALL_TYPES.map((t) => ({ value: t, label: i18n.t(typeLabelKeys[t]!) })),
)

const typeValue = computed<string[]>({
  get: () => selectedTypes.value ?? [...ALL_TYPES],
  set: (v) => {
    selectedTypes.value = v.length >= ALL_TYPES.length ? null : (v as QuestionType[])
  },
})
</script>

<template>
  <div class="space-y-4">
    <div>
      <div class="text-label-lg mb-2" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchBankScope') }}</div>
      <BaseMultiSelect v-model="bankValue" :options="bankOptions" :placeholder="i18n.t('searchAllBanks')" />
    </div>

    <div>
      <div class="text-label-lg mb-2" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchMatchScope') }}</div>
      <BaseSelect v-model="scope" :options="scopeOptions" />
    </div>

    <div>
      <div class="text-label-lg mb-2" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchTypeFilter') }}</div>
      <BaseMultiSelect v-model="typeValue" :options="typeOptions" :searchable="false" />
    </div>
  </div>
</template>
