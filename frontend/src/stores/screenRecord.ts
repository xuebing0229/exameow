import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Question } from '@exameow/shared'

export interface ScreenRegion {
  x: number
  y: number
  w: number
  h: number
}

export interface MatchResult {
  question: Question
  bankName: string
  score: number
}

export const useScreenRecordStore = defineStore('screenRecord', () => {
  const status = ref<'idle' | 'adjusting' | 'recording' | 'paused'>('idle')
  const mode = ref<'continuous' | 'floating'>('continuous')
  const region = ref<ScreenRegion>({
    x: 100,
    y: 100,
    w: 700,
    h: 300,
  })
  const currentResult = ref<MatchResult | null>(null)
  const ocrText = ref('')
  const ocrError = ref('')
  const overlayVisible = ref(true)
  const collapsed = ref(false)

  const isRecording = computed(() => status.value === 'recording')
  const answerText = computed(() => {
    if (!currentResult.value) return null
    return currentResult.value.question.answer
  })

  function startRecording(nextMode: 'continuous' | 'floating' = 'continuous') {
    mode.value = nextMode
    status.value = 'adjusting'
    currentResult.value = null
    ocrText.value = ''
  }

  function beginRecording() {
    status.value = 'recording'
  }

  function pauseToAdjust() {
    if (status.value === 'recording') status.value = 'adjusting'
  }

  function stopRecording() {
    status.value = 'idle'
    currentResult.value = null
    ocrText.value = ''
    ocrError.value = ''
  }

  function setRegion(r: Partial<ScreenRegion>) {
    region.value = { ...region.value, ...r }
  }

  function setResult(result: MatchResult | null, text: string) {
    currentResult.value = result
    ocrText.value = text
  }

  function toggleOverlay() {
    overlayVisible.value = !overlayVisible.value
  }

  function setCollapsed(v: boolean) {
    collapsed.value = v
  }

  return {
    status,
    mode,
    region,
    currentResult,
    ocrText,
    ocrError,
    overlayVisible,
    collapsed,
    isRecording,
    answerText,
    startRecording,
    beginRecording,
    pauseToAdjust,
    stopRecording,
    setRegion,
    setResult,
    toggleOverlay,
    setCollapsed,
  }
})
