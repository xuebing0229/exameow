import { useCameraLiveStore, type MatchResult } from '@/stores/cameraLive'
import { recognizeImage, preloadOcr } from '@/utils/ocr'
import { searchQuestions, decideScanResult } from '@/utils/questionSearch'
import { getSearchSettings } from '@/composables/useSearchSettings'
import { usePracticeStore } from '@/stores/practice'

type TimerHandle = ReturnType<typeof setInterval> | null

let stream: MediaStream | null = null
let timer: TimerHandle = null
let processBusy = false
let pendingBitmap: ImageBitmap | null = null

function log(...args: unknown[]) {
  console.log('[拍屏搜题]', ...args)
  import('@tauri-apps/api/core')
    .then(({ invoke }) => invoke('frontend_log', { msg: `[拍屏搜题] ${args.join(' ')}` }).catch(() => {}))
    .catch(() => {})
}

function enqueueBitmap(bitmap: ImageBitmap) {
  if (processBusy) {
    if (pendingBitmap) pendingBitmap.close()
    pendingBitmap = bitmap
    return
  }
  processBusy = true
  void (async () => {
    let current: ImageBitmap | null = bitmap
    try {
      while (current) {
        const bmp = current
        current = null
        try {
          await processFrame(bmp)
        } catch (e) {
          log('frame failed:', e instanceof Error ? e.message : String(e))
        }
        bmp.close()
        current = pendingBitmap
        pendingBitmap = null
      }
    } finally {
      processBusy = false
    }
  })()
}

async function processFrame(bitmap: ImageBitmap) {
  const store = useCameraLiveStore()
  if (store.status !== 'scanning') return

  const MAX_SIDE = 1200
  const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(bitmap.width * scale))
  canvas.height = Math.max(1, Math.round(bitmap.height * scale))
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)

  const t1 = performance.now()
  const text = await recognizeImage(canvas)
  const ocrMs = Math.round(performance.now() - t1)

  if (store.status !== 'scanning') return

  if (!text.trim()) {
    store.setResult(null, '')
    return
  }
  if (text.replace(/\s+/g, '').length < 8) {
    store.setResult(null, text)
    return
  }

  log(`OCR ${ocrMs}ms:`, text.slice(0, 80))

  const practiceStore = usePracticeStore()
  const settings = getSearchSettings()
  const hits = searchQuestions(text, practiceStore.banks, {
    bankIds: settings.bankIds,
    scope: settings.scope,
    types: settings.types,
    mode: 'scan',
  })

  const decision = decideScanResult(store.currentResult?.question ?? null, hits)

  if (decision.action === 'set' && decision.hit) {
    store.setResult(
      {
        question: decision.hit.question,
        bankName: decision.hit.bankName,
        score: decision.hit.score,
      },
      text,
    )
  } else if (decision.action === 'clear') {
    store.setResult(null, text)
  }
}

async function captureFrame(video: HTMLVideoElement) {
  const store = useCameraLiveStore()
  if (store.status !== 'scanning') return

  const canvas = document.createElement('canvas')
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) return
  ctx.drawImage(video, 0, 0)

  const bitmap = await createImageBitmap(canvas)
  enqueueBitmap(bitmap)
}

function startTimer(video: HTMLVideoElement) {
  if (timer) clearInterval(timer)
  timer = setInterval(() => captureFrame(video), 1500)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function startCamera(video: HTMLVideoElement) {
  preloadOcr().catch((e) => {
    const store = useCameraLiveStore()
    store.ocrError = e instanceof Error ? e.message : String(e)
  })

  stream = await navigator.mediaDevices.getUserMedia({
    video: { facingMode: 'environment' },
    audio: false,
  })
  video.srcObject = stream
  await video.play()

  startTimer(video)
}

function stopCamera() {
  stopTimer()
  if (pendingBitmap) {
    pendingBitmap.close()
    pendingBitmap = null
  }
  if (stream) {
    stream.getTracks().forEach((t) => t.stop())
    stream = null
  }
}

async function pauseCamera() {
  stopTimer()
}

async function resumeCamera(video: HTMLVideoElement) {
  if (!stream) return
  startTimer(video)
}

export function useCameraLive() {
  return { startCamera, stopCamera, pauseCamera, resumeCamera }
}
