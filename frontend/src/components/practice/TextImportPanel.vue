<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Question } from '@exameow/shared'
import { QuestionType } from '@exameow/shared'
import { api } from '@/api'
import { useConfigStore } from '@/stores/config'
import { usePracticeStore } from '@/stores/practice'
import {
  parseTextQuestions,
  validateTextImportQuestions,
  type TextImportIssue,
} from '@/utils/textImportParser'
import {
  ArrowPathIcon,
  DocumentArrowUpIcon,
  ExclamationTriangleIcon,
  SparklesIcon,
  TrashIcon,
} from '@heroicons/vue/24/outline'

const emit = defineEmits<{ (e: 'imported', count: number): void }>()
const practiceStore = usePracticeStore()
const configStore = useConfigStore()

const textFileInput = ref<HTMLInputElement | null>(null)
const rawText = ref('')
const fileName = ref('粘贴题库.txt')
const questions = ref<Question[]>([])
const unparsedBlocks = ref<string[]>([])
const parseMessage = ref('')
const aiNotes = ref<string[]>([])
const busy = ref(false)
const onlyAnomalies = ref(false)
const abortController = ref<AbortController | null>(null)

const typeOptions = [
  { value: QuestionType.SingleChoice, label: '单选题' },
  { value: QuestionType.MultiChoice, label: '多选题' },
  { value: QuestionType.TrueFalse, label: '判断题' },
  { value: QuestionType.FillBlank, label: '填空题' },
  { value: QuestionType.ShortAnswer, label: '简答题' },
]

const issues = computed<TextImportIssue[]>(() => {
  const current = validateTextImportQuestions(questions.value)
  if (unparsedBlocks.value.length > 0) {
    current.unshift({ level: 'warning', message: `有 ${unparsedBlocks.value.length} 段文本未能归入题目` })
  }
  return current
})
const issueIds = computed(() => new Set(issues.value.map(issue => issue.questionId).filter(Boolean)))
const errorCount = computed(() => issues.value.filter(issue => issue.level === 'error').length)
const warningCount = computed(() => issues.value.filter(issue => issue.level === 'warning').length)
const visibleQuestions = computed(() => questions.value
  .map((question, index) => ({ question, index }))
  .filter(item => !onlyAnomalies.value || issueIds.value.has(item.question.id))
)

function issuesFor(question: Question): TextImportIssue[] {
  return issues.value.filter(issue => issue.questionId === question.id)
}

function optionsText(question: Question): string {
  return question.options.join('\n')
}

function updateOptions(question: Question, value: string) {
  question.options = value.split(/\r?\n/).map(option => option.trim()).filter(Boolean)
}

function handleOptionsInput(question: Question, event: Event) {
  updateOptions(question, (event.target as HTMLTextAreaElement).value)
}

function runLocalParse(message = '本地解析完成') {
  aiNotes.value = []
  const result = parseTextQuestions(rawText.value)
  questions.value = result.questions
  unparsedBlocks.value = result.unparsedBlocks
  parseMessage.value = result.questions.length
    ? `${message}：识别到 ${result.questions.length} 题。`
    : '没有识别到题目，请调整文本格式或使用 AI 整理。'
}

async function handleTextFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  fileName.value = file.name
  rawText.value = await file.text()
  runLocalParse(`已读取 ${file.name}`)
}

function loadExample() {
  fileName.value = '示例题库.txt'
  rawText.value = `一、单项选择题
1. 中国的首都是哪里？
A. 北京
B. 上海
C. 广州
D. 深圳
答案：A
解析：北京是中国的首都。

二、多项选择题
2. 以下哪些是直辖市？
A. 北京
B. 上海
C. 广州
D. 重庆
答案：ABD

三、判断题
3. 地球围绕太阳运行。
答案：正确`
  runLocalParse('已载入示例')
}

async function refactorWithAI() {
  if (!rawText.value.trim() || busy.value) return
  if ([...rawText.value].length > 60_000) {
    parseMessage.value = '原文超过 60000 字，请拆分后再进行 AI 整理。'
    return
  }
  if (!configStore.configured) await configStore.loadSaved()
  if (!configStore.configured) {
    parseMessage.value = '请先在“我的 → 算力配置”中配置 AI 接口。'
    return
  }
  busy.value = true
  abortController.value = new AbortController()
  const beforeCount = questions.value.length
  try {
    const result = await api.refactorTextImport({
      raw_text: rawText.value,
      current_questions: questions.value,
      warnings: issues.value.map(issue => `${issue.level}: ${issue.questionNumber ? `第${issue.questionNumber}题 ` : ''}${issue.message}`),
    }, configStore.getConfig(), abortController.value.signal)
    rawText.value = result.cleaned_text
    aiNotes.value = result.notes
    runLocalParse('AI 已整理格式并重新本地解析')
    if (beforeCount > 0 && questions.value.length !== beforeCount) {
      aiNotes.value.unshift(`整理前 ${beforeCount} 题，整理后 ${questions.value.length} 题，请重点核对题数变化。`)
    }
  } catch (error: any) {
    if (error?.name !== 'AbortError') parseMessage.value = `AI 整理失败：${error?.message || String(error)}`
  } finally {
    busy.value = false
    abortController.value = null
  }
}

function removeQuestion(index: number) {
  questions.value.splice(index, 1)
}

function confirmImport() {
  if (questions.value.length === 0 || errorCount.value > 0) return
  const count = questions.value.length
  practiceStore.importQuestionList(questions.value, fileName.value)
  emit('imported', count)
}
</script>

<template>
  <div class="space-y-4">
    <input ref="textFileInput" type="file" accept=".txt,.md,.text" class="hidden" @change="handleTextFile" />

    <div class="flex flex-wrap gap-2">
      <button class="btn-tonal flex-1 min-w-[130px]" @click="textFileInput?.click()">
        <DocumentArrowUpIcon class="w-4 h-4" /> 选择 TXT 文件
      </button>
      <button class="btn-text flex-1 min-w-[130px]" @click="loadExample">载入格式示例</button>
    </div>

    <label class="block">
      <span class="block text-body-sm mb-1" style="color: rgb(var(--md-on-surface-variant))">
        原始题库文本（可直接粘贴和修改）
      </span>
      <textarea
        v-model="rawText"
        class="w-full min-h-52 px-3 py-2 rounded-xl text-sm bg-transparent outline-none resize-y"
        style="border: 1px solid rgb(var(--md-outline-variant)); color: rgb(var(--md-on-surface)); background: rgb(var(--md-surface-container-low))"
        placeholder="把题库文本粘贴到这里，或选择 TXT 文件。支持题号、A-H 选项、答案、解析及题型分区。"
      />
    </label>

    <div class="flex flex-wrap gap-2">
      <button class="btn-filled flex-1 min-w-[130px]" :disabled="!rawText.trim() || busy" @click="runLocalParse()">
        <ArrowPathIcon class="w-4 h-4" /> 本地识别
      </button>
      <button class="btn-tonal flex-1 min-w-[150px]" :disabled="!rawText.trim() || busy" @click="refactorWithAI">
        <SparklesIcon class="w-4 h-4" /> {{ busy ? 'AI 整理中…' : 'AI 整理格式' }}
      </button>
      <button v-if="busy" class="btn-text" @click="abortController?.abort()">停止</button>
    </div>

    <div v-if="parseMessage" class="card-outlined p-3 text-body-sm" style="color: rgb(var(--md-on-surface-variant))">
      {{ parseMessage }}
    </div>
    <div v-if="aiNotes.length" class="card-outlined p-3 space-y-1" style="border-color: rgb(var(--md-tertiary))">
      <div class="text-title-sm">AI 整理说明</div>
      <div v-for="(note, index) in aiNotes" :key="index" class="text-body-sm">• {{ note }}</div>
    </div>

    <template v-if="questions.length">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div class="text-title-sm">
          识别结果：{{ questions.length }} 题
          <span v-if="errorCount" style="color: rgb(var(--md-error))"> · {{ errorCount }} 个错误</span>
          <span v-if="warningCount" style="color: rgb(var(--md-tertiary))"> · {{ warningCount }} 个提醒</span>
        </div>
        <button class="btn-text text-sm" :class="{ 'btn-tonal': onlyAnomalies }" @click="onlyAnomalies = !onlyAnomalies">
          {{ onlyAnomalies ? '显示全部题' : '仅看异常题' }}
        </button>
      </div>

      <div v-if="onlyAnomalies && visibleQuestions.length === 0" class="card-outlined p-4 text-center text-body-sm">
        当前没有异常题。
      </div>

      <div v-for="item in visibleQuestions" :key="item.question.id" class="card-outlined p-4 space-y-3">
        <div class="flex items-start justify-between gap-3">
          <div class="flex items-center gap-2">
            <span class="text-title-sm">第 {{ item.index + 1 }} 题</span>
            <span v-if="issuesFor(item.question).length" class="inline-flex items-center gap-1 text-xs" style="color: rgb(var(--md-error))">
              <ExclamationTriangleIcon class="w-4 h-4" /> 需核对
            </span>
          </div>
          <button class="btn-icon !w-8 !h-8" style="color: rgb(var(--md-error))" @click="removeQuestion(item.index)">
            <TrashIcon class="w-4 h-4" />
          </button>
        </div>

        <div v-if="issuesFor(item.question).length" class="space-y-1">
          <div
            v-for="(issue, issueIndex) in issuesFor(item.question)"
            :key="issueIndex"
            class="text-xs"
            :style="{ color: issue.level === 'error' ? 'rgb(var(--md-error))' : 'rgb(var(--md-tertiary))' }"
          >
            {{ issue.level === 'error' ? '错误' : '提醒' }}：{{ issue.message }}
          </div>
        </div>

        <label class="block">
          <span class="block text-xs mb-1">题型</span>
          <select v-model="item.question.type" class="w-full px-3 py-2 rounded-xl text-sm" style="border: 1px solid rgb(var(--md-outline-variant)); background: rgb(var(--md-surface-container-low)); color: rgb(var(--md-on-surface))">
            <option v-for="option in typeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <label class="block">
          <span class="block text-xs mb-1">题干</span>
          <textarea v-model="item.question.stem" rows="3" class="w-full px-3 py-2 rounded-xl text-sm bg-transparent" style="border: 1px solid rgb(var(--md-outline-variant))" />
        </label>
        <label v-if="item.question.type === QuestionType.SingleChoice || item.question.type === QuestionType.MultiChoice" class="block">
          <span class="block text-xs mb-1">选项（每行一个，按 A、B、C… 排列）</span>
          <textarea :value="optionsText(item.question)" rows="4" class="w-full px-3 py-2 rounded-xl text-sm bg-transparent" style="border: 1px solid rgb(var(--md-outline-variant))" @input="handleOptionsInput(item.question, $event)" />
        </label>
        <label class="block">
          <span class="block text-xs mb-1">答案</span>
          <input v-model="item.question.answer" class="w-full px-3 py-2 rounded-xl text-sm bg-transparent" style="border: 1px solid rgb(var(--md-outline-variant))" />
        </label>
        <label class="block">
          <span class="block text-xs mb-1">解析</span>
          <textarea v-model="item.question.analysis" rows="2" class="w-full px-3 py-2 rounded-xl text-sm bg-transparent" style="border: 1px solid rgb(var(--md-outline-variant))" />
        </label>
      </div>

      <div v-if="unparsedBlocks.length" class="card-outlined p-3" style="border-color: rgb(var(--md-tertiary))">
        <div class="text-title-sm mb-2">未归入题目的文本</div>
        <pre class="text-xs whitespace-pre-wrap max-h-40 overflow-y-auto">{{ unparsedBlocks.join('\n') }}</pre>
      </div>

      <button class="btn-filled w-full" :disabled="errorCount > 0" @click="confirmImport">
        {{ errorCount > 0 ? '请先修正硬错误' : `确认导入 ${questions.length} 题` }}
      </button>
    </template>
  </div>
</template>
