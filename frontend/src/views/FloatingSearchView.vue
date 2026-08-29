<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18nStore } from '@/stores/i18n'
import { useScreenRecordStore } from '@/stores/screenRecord'
import { isAndroid, isTauri } from '@/utils/platform'
import { ArrowLeftIcon, CursorArrowRaysIcon, AdjustmentsHorizontalIcon } from '@heroicons/vue/24/outline'
import SearchSettingsPanel from '@/components/search/SearchSettingsPanel.vue'

const router = useRouter()
const i18n = useI18nStore()
const store = useScreenRecordStore()

const supported = isTauri() && isAndroid()
const showSettings = ref(false)
const enabled = computed(() => store.mode === 'floating' && store.status !== 'idle')

async function startFloatingSearch() {
  store.startRecording('floating')
  const { useScreenRecord } = await import('@/composables/useScreenRecord')
  const { startFloating } = useScreenRecord()
  try {
    await startFloating()
  } catch (e) {
    store.stopRecording()
    const msg = String(e)
    if (msg.includes('overlay_permission')) {
      alert(i18n.t('searchScreenRecordOverlayPerm'))
    } else if (msg.includes('accessibility_service_required')) {
      alert('请开启“Exameow 悬浮搜题截图”无障碍服务，返回后再次点击开启悬浮窗。若开关不可用，请先在 Exameow 的应用信息右上角菜单中选择“允许受限设置”。')
    } else if (msg.includes('accessibility_screenshot_unsupported')) {
      alert('无录屏悬浮搜题需要 Android 11 或更高版本')
    } else if (msg.includes('projection_denied')) {
      alert(i18n.t('searchScreenRecordProjectionDenied'))
    } else {
      console.warn('[悬浮窗搜题] start failed:', e)
    }
  }
}

async function stopFloatingSearch() {
  const { useScreenRecord } = await import('@/composables/useScreenRecord')
  await useScreenRecord().stop()
}
</script>

<template>
  <div>
    <div class="flex items-center gap-2 mb-1">
      <button class="btn-icon" @click="router.push('/search')">
        <ArrowLeftIcon class="w-5 h-5" />
      </button>
      <h1 class="text-display-sm flex-1">{{ i18n.t('searchModeFloating') }}</h1>
    </div>
    <p class="text-body-lg mb-4" style="color: rgb(var(--md-on-surface-variant))">
      {{ i18n.t('searchFloatingDesc') }}
    </p>

    <div v-if="!supported" class="card-filled p-6 text-center">
      <CursorArrowRaysIcon class="w-12 h-12 mx-auto mb-3" style="color: rgb(var(--md-on-surface-variant))" />
      <p class="text-body-md" style="color: rgb(var(--md-on-surface-variant))">
        {{ i18n.t('searchFloatingNotSupported') }}
      </p>
    </div>

    <div v-else class="card-filled p-6 flex flex-col gap-4">
      <div class="flex items-start justify-end">
        <button
          class="btn-tonal text-sm shrink-0 !px-3 !py-2"
          :style="{ color: showSettings ? 'rgb(var(--md-primary))' : 'rgb(var(--md-on-surface-variant))' }"
          @click="showSettings = !showSettings"
        >
          <AdjustmentsHorizontalIcon class="w-4 h-4" />
          <span>{{ i18n.t('searchSettings') }}</span>
        </button>
      </div>

      <div class="flex flex-col items-center gap-4 text-center">
        <div
          class="w-16 h-16 rounded-full flex items-center justify-center"
          :style="{ backgroundColor: 'rgb(var(--md-primary-container))', color: 'rgb(var(--md-on-primary-container))' }"
        >
          <CursorArrowRaysIcon class="w-8 h-8" />
        </div>
        <template v-if="enabled">
          <p class="text-body-md" style="color: rgb(var(--md-primary))">
            {{ i18n.t('searchFloatingEnabled') }}
          </p>
          <button class="btn-tonal" @click="stopFloatingSearch">
            {{ i18n.t('searchFloatingStop') }}
          </button>
        </template>
        <button
          v-else
          class="px-8 py-3 rounded-full text-title-md font-medium cursor-pointer"
          :style="{ backgroundColor: 'rgb(var(--md-primary))', color: 'rgb(var(--md-on-primary))' }"
          @click="startFloatingSearch"
        >
          {{ i18n.t('searchFloatingStart') }}
        </button>
      </div>

      <div v-if="showSettings" class="pt-4" style="border-top: 1px solid rgb(var(--md-outline-variant) / 0.4)">
        <SearchSettingsPanel />
      </div>
    </div>
  </div>
</template>
