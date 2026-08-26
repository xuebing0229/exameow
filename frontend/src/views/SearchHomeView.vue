<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18nStore } from '@/stores/i18n'
import { isTauri, isDesktopTauri, isMobileDevice, isAndroid } from '@/utils/platform'
import {
  DocumentTextIcon,
  CameraIcon,
  VideoCameraIcon,
  ComputerDesktopIcon,
  CursorArrowRaysIcon,
} from '@heroicons/vue/24/outline'

const router = useRouter()
const i18n = useI18nStore()

const isTauriMobile = isTauri() && isMobileDevice()

const modes = [
  { key: 'text', titleKey: 'searchModeText', descKey: 'searchModeTextDesc', icon: DocumentTextIcon, available: true, supported: true, path: '/search/text' },
  { key: 'photo', titleKey: 'searchModePhoto', descKey: 'searchModePhotoDesc', icon: CameraIcon, available: true, supported: true, path: '/search/photo' },
  { key: 'floating', titleKey: 'searchModeFloating', descKey: 'searchModeFloatingDesc', icon: CursorArrowRaysIcon, available: true, supported: isTauri() && isAndroid(), path: '/search/floating' },
  { key: 'screenRecord', titleKey: 'searchModeScreenRecord', descKey: 'searchModeScreenRecordDesc', icon: ComputerDesktopIcon, available: true, supported: isDesktopTauri() || (isTauri() && isAndroid()), path: '/search/screen-record' },
  { key: 'cameraLive', titleKey: 'searchModeCameraLive', descKey: 'searchModeCameraLiveDesc', icon: VideoCameraIcon, available: true, supported: isTauriMobile, path: '/search/camera-live' },
] as const

function open(mode: (typeof modes)[number]) {
  if (mode.available && mode.supported && mode.path) router.push(mode.path)
}
</script>

<template>
  <div>
    <h1 class="text-display-sm mb-1">{{ i18n.t('searchTitle') }}</h1>
    <p class="text-body-lg mb-6" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t('searchSubtitle') }}</p>

    <div class="grid grid-cols-1 sm:grid-cols-2 gap-4 sm:gap-5">
      <button
        v-for="mode in modes"
        :key="mode.key"
        class="card-filled p-5 text-left flex items-start gap-4 transition-all duration-200"
        :class="mode.available && mode.supported ? 'cursor-pointer hover:shadow-md' : 'cursor-not-allowed opacity-50'"
        :disabled="!mode.available || !mode.supported"
        @click="open(mode)"
      >
        <div
          class="w-12 h-12 rounded-2xl flex items-center justify-center shrink-0"
          :style="{ backgroundColor: 'rgb(var(--md-secondary-container))', color: 'rgb(var(--md-on-secondary-container))' }"
        >
          <component :is="mode.icon" class="w-6 h-6" />
        </div>
        <div class="min-w-0">
          <div class="flex items-center gap-2 flex-wrap">
            <span class="text-title-md">{{ i18n.t(mode.titleKey) }}</span>
            <span
              v-if="!mode.supported"
              class="text-[11px] font-medium px-2 py-0.5 rounded-full"
              :style="{ backgroundColor: 'rgb(var(--md-surface-container-highest))', color: 'rgb(var(--md-on-surface-variant))' }"
            >
              {{ i18n.t('searchNotSupported') }}
            </span>
            <span
              v-else-if="!mode.available"
              class="text-[11px] font-medium px-2 py-0.5 rounded-full"
              :style="{ backgroundColor: 'rgb(var(--md-surface-container-highest))', color: 'rgb(var(--md-on-surface-variant))' }"
            >
              {{ i18n.t('searchComingSoon') }}
            </span>
          </div>
          <p class="text-body-sm mt-1" style="color: rgb(var(--md-on-surface-variant))">{{ i18n.t(mode.descKey) }}</p>
        </div>
      </button>
    </div>
  </div>
</template>
