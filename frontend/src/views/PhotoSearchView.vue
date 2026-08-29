<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useI18nStore } from '@/stores/i18n'
import { useImageSearch } from '@/composables/useImageSearch'
import { isMobileDevice } from '@/utils/platform'
import {
  ArrowLeftIcon,
  CameraIcon,
  PhotoIcon,
  ArrowPathIcon,
} from '@heroicons/vue/24/outline'
import { AdjustmentsHorizontalIcon } from '@heroicons/vue/24/outline'
import SearchPanel from '@/components/search/SearchPanel.vue'
import SearchSettingsPanel from '@/components/search/SearchSettingsPanel.vue'

const router = useRouter()
const i18n = useI18nStore()
const { phase, error, busy, recognize, cancel } = useImageSearch()

const cameraInput = ref<HTMLInputElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const previewUrl = ref('')
const query = ref('')
const recognizedEmpty = ref(false)
const dragging = ref(false)
let currentFile: File | null = null

const showCamera = isMobileDevice()
const showSettings = ref(false)

function pickCamera() {
  cameraInput.value?.click()
}

function pickFile() {
  fileInput.value?.click()
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) handleFile(file)
}

function onDrop(e: DragEvent) {
  dragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file && file.type.startsWith('image/')) handleFile(file)
}

async function handleFile(file: File) {
  currentFile = file
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = URL.createObjectURL(file)
  query.value = ''
  recognizedEmpty.value = false
  const text = await recognize(file)
  if (text !== null) {
    query.value = text
    recognizedEmpty.value = !text.trim()
  }
}
onBeforeUnmount(() => {
  cancel()
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <div>
    <div class="flex items-center gap-2 mb-1">
      <button class="btn-icon" @click="router.push('/search')">
        <ArrowLeftIcon class="w-5 h-5" />
      </button>
      <h1 class="text-display-sm flex-1">{{ i18n.t('searchModePhoto') }}</h1>
    </div>
    <p class="text-body-lg mb-4" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchModePhotoDesc') }}</p>

    <input ref="cameraInput" type="file" accept="image/*" capture="environment" class="hidden" @change="onFileChange" />
    <input ref="fileInput" type="file" accept="image/*" class="hidden" @change="onFileChange" />

    <div
      class="card-filled p-5 mb-4 transition-all"
      :style="dragging ? { outline: '2px dashed rgb(var(--md-primary))' } : {}"
      @dragover.prevent="dragging = true"
      @dragleave="dragging = false"
      @drop.prevent="onDrop"
    >
      <div class="flex items-start justify-end gap-2 mb-4">
        <button
          class="btn-tonal text-sm shrink-0 !px-3 !py-2"
          :style="{ color: showSettings ? 'rgb(var(--md-primary))' : 'rgb(var(--md-on-surface-variant))' }"
          @click="showSettings = !showSettings"
          :title="i18n.t('searchSettings')"
        >
          <AdjustmentsHorizontalIcon class="w-4 h-4" />
          <span class="hidden sm:inline">{{ i18n.t('searchSettings') }}</span>
        </button>
      </div>

      <template v-if="!previewUrl">
        <div class="flex flex-col items-center gap-4 text-center">
          <div
            class="w-16 h-16 rounded-full flex items-center justify-center"
            :style="{ backgroundColor: 'rgb(var(--md-primary-container))', color: 'rgb(var(--md-on-primary-container))' }"
          >
            <CameraIcon class="w-8 h-8" />
          </div>
          <div class="flex items-center gap-3">
            <button v-if="showCamera"
              class="px-8 py-3 rounded-full text-title-md font-medium transition-all duration-200 cursor-pointer"
              :style="{ backgroundColor: 'rgb(var(--md-primary))', color: 'rgb(var(--md-on-primary))' }"
              @click="pickCamera"
            >
              {{ i18n.t('searchPhotoTake') }}
            </button>
            <button
              class="px-8 py-3 rounded-full text-title-md font-medium transition-all duration-200 cursor-pointer"
              :style="{ backgroundColor: 'rgb(var(--md-secondary-container))', color: 'rgb(var(--md-on-secondary-container))' }"
              @click="pickFile"
            >
              {{ i18n.t('searchPhotoUpload') }}
            </button>
          </div>
        </div>
      </template>

      <template v-else>
        <img :src="previewUrl" class="max-h-72 mx-auto rounded-2xl mb-4 object-contain" />
        <div class="flex items-center justify-center gap-3 flex-wrap">
          <button v-if="showCamera" class="btn-tonal text-sm" :disabled="busy" @click="pickCamera">
            <CameraIcon class="w-4 h-4" />
            {{ i18n.t('searchPhotoTake') }}
          </button>
          <button class="btn-tonal text-sm" :disabled="busy" @click="pickFile">
            <ArrowPathIcon class="w-4 h-4" />
            {{ i18n.t('searchPhotoReselect') }}
          </button>
        </div>
      </template>

      <div v-if="showSettings" class="mt-4 pt-4" style="border-top: 1px solid rgb(var(--md-outline-variant) / 0.4)">
        <SearchSettingsPanel />
      </div>
    </div>

    <div v-if="busy" class="card-outlined p-4 mb-4 flex items-center justify-between">
      <span class="text-body-md" style="color: rgb(var(--md-on-surface-variant))">
        {{ phase === 'loading-model' ? i18n.t('searchPhotoLoadingModel') : i18n.t('searchPhotoRecognizing') }}
      </span>
      <button class="btn-outlined !h-8 text-xs !px-3" @click="cancel">{{ i18n.t('searchCancel') }}</button>
    </div>

    <div v-else-if="error" class="card-outlined p-4 mb-4">
      <p class="text-body-md mb-3" :style="{ color: 'rgb(var(--md-error))' }">{{ error }}</p>
      <div class="flex gap-2 flex-wrap">
        <button class="btn-tonal !h-8 text-xs !px-3" @click="currentFile && handleFile(currentFile)">
          {{ i18n.t('searchRetry') }}
        </button>
      </div>
    </div>

    <p v-if="recognizedEmpty && !busy && !error" class="text-body-sm mb-4" style="color: rgb(var(--md-on-surface-variant))">
      {{ i18n.t('searchPhotoEmpty') }}
    </p>

    <SearchPanel
      v-if="previewUrl && !busy"
      :key="previewUrl"
      v-model:query="query"
      query-placement="bottom"
      collapsible-query
      :show-query-settings="false"
    />
  </div>
</template>
