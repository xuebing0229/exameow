import { useScreenRecordStore, type ScreenRegion } from '@/stores/screenRecord'
import { api } from '@/api'
import { recognizeImage, preloadOcr } from '@/utils/ocr'
import { searchQuestions, decideScanResult } from '@/utils/questionSearch'
import { getSearchSettings } from '@/composables/useSearchSettings'
import { usePracticeStore } from '@/stores/practice'
import { isAndroid, isMacOS } from '@/utils/platform'

type TimerHandle = ReturnType<typeof setInterval> | null

let timer: TimerHandle = null
let captureBusy = false
let captureQueued = false
let floatUnlistenFns: Array<() => void> = []

// 心跳兜底：距上次真正 OCR 超过该时长则强制截屏，防止去重漏检后永久卡住
const HEARTBEAT_MS = 5000
let lastFrameAt = 0

function log(...args: unknown[]) {
  console.log('[录屏搜题]', ...args)
  const msg = args.map((a) => (typeof a === 'string' ? a : JSON.stringify(a))).join(' ')
  import('@tauri-apps/api/core')
    .then(({ invoke }) => invoke('frontend_log', { msg: `[录屏搜题] ${msg}` }).catch(() => {}))
    .catch(() => {})
}

// ---- 帧处理队列：只保留最新待处理帧，过期帧直接丢弃 ----
// 截屏（IPC）与 OCR（WASM 线程）由此并行，吞吐约提升一倍
interface PendingFrame {
  bitmap: ImageBitmap
  done: () => void
}
let processBusy = false
let pendingFrame: PendingFrame | null = null

function enqueueFrame(bitmap: ImageBitmap): Promise<void> {
  return new Promise((resolve) => {
    if (processBusy) {
      if (pendingFrame) {
        pendingFrame.bitmap.close()
        pendingFrame.done()
      }
      pendingFrame = { bitmap, done: resolve }
      return
    }
    processBusy = true
    void (async () => {
      let current: PendingFrame | null = { bitmap, done: resolve }
      try {
        while (current) {
          const frame = current
          current = null
          try {
            await processFrameBitmap(frame.bitmap)
          } catch (e) {
            log('frame failed:', e instanceof Error ? e.message : String(e))
          }
          frame.bitmap.close()
          frame.done()
          current = pendingFrame
          pendingFrame = null
        }
      } finally {
        processBusy = false
      }
    })()
  })
}

async function processFrameBitmap(bitmap: ImageBitmap) {
  const store = useScreenRecordStore()
  if (store.status !== 'recording') return

  const MAX_SIDE = 1280
  const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(bitmap.width * scale))
  canvas.height = Math.max(1, Math.round(bitmap.height * scale))
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)

  // 画布为空（webview 被遮挡时 drawImage 可能得到全零像素）则跳过本帧
  const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data
  let sum = 0
  for (let i = 0; i < pixels.length; i += 4096 * 4) {
    sum += pixels[i]! + pixels[i + 1]! + pixels[i + 2]!
  }
  if (sum === 0) {
    log('画布全零（窗口可能被遮挡），丢弃本帧')
    return
  }

  const t1 = performance.now()
  log(`开始 OCR (${canvas.width}x${canvas.height})`)
  const text = await recognizeImage(canvas)
  const ocrMs = Math.round(performance.now() - t1)

  if (store.status !== 'recording') {
    log('识别完成时已暂停，丢弃结果')
    return
  }
  if (!text.trim()) {
    log(`OCR ${ocrMs}ms，无文本`)
    return
  }
  if (text.replace(/\s+/g, '').length < 8) {
    log(`OCR ${ocrMs}ms，文本过短，忽略:`, text.slice(0, 30))
    return
  }
  log(`OCR ${ocrMs}ms (${canvas.width}x${canvas.height}):`, text.slice(0, 100) + (text.length > 100 ? '…' : ''))

  const practiceStore = usePracticeStore()
  const settings = getSearchSettings()
  const hits = searchQuestions(text, practiceStore.banks, {
    bankIds: settings.bankIds,
    scope: settings.scope,
    types: settings.types,
    mode: 'search',
  })

  log('候选:', hits.length
    ? hits.slice(0, 3).map((hh) => `${hh.question.stem.slice(0, 16)}…=${hh.score.toFixed(2)}`).join(' | ')
    : '无')

  const decision = decideScanResult(store.currentResult?.question ?? null, hits)
  log('决策:', decision.reason, `→ ${decision.action}`)

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

async function captureOnce() {
  const store = useScreenRecordStore()
  if (store.status !== 'recording') return
  try {
    const { x, y, w, h } = store.region
    const force = performance.now() - lastFrameAt > HEARTBEAT_MS
    const t0 = performance.now()
    const bytes = await api.captureScreen(x, y, w, h, force)
    const ms = Math.round(performance.now() - t0)
    if (bytes.byteLength === 0) {
      log(`画面无变化，跳过 (${ms}ms)`)
      return
    }
    lastFrameAt = performance.now()
    log(`截图 ${ms}ms${force ? '（心跳强制）' : ''}`)
    const bitmap = await createImageBitmap(new Blob([bytes as BlobPart], { type: 'image/jpeg' }))
    void enqueueFrame(bitmap)
  } catch (e) {
    console.warn('[录屏搜题] capture failed:', e)
  }
}

async function capture() {
  if (captureBusy) {
    captureQueued = true
    return
  }
  captureBusy = true
  try {
    do {
      captureQueued = false
      await captureOnce()
    } while (captureQueued)
  } finally {
    captureBusy = false
  }
}

function startTimer() {
  if (timer) clearInterval(timer)
  timer = setInterval(capture, 1500)
}

function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

export function useScreenRecord() {
  const store = useScreenRecordStore()

  // ===== Android (mobile) side: native plugin drives capture; the webview
  // receives cropped JPEG frames over a Channel and pushes results back =====

  async function processFrameMobile(dataUrl: string) {
    try {
      log(`移动端帧到达 len=${dataUrl.length}`)
      const img = new Image()
      await new Promise<void>((resolve, reject) => {
        img.onload = () => resolve()
        img.onerror = () => reject(new Error('Failed to load frame'))
        img.src = dataUrl
      })
      const bitmap = await createImageBitmap(img)
      log(`位图就绪 ${bitmap.width}x${bitmap.height}`)
      await enqueueFrame(bitmap)
    } catch (e) {
      log('mobile frame failed:', e instanceof Error ? e.message : String(e))
    }
    await pushAnswerToOverlay()
  }

  async function pushAnswerToOverlay() {
    const r = store.currentResult
    try {
      const { invoke } = await import('@tauri-apps/api/core')
      const dark = document.documentElement.classList.contains('dark')
      await invoke('plugin:screenrecord|show_answer', {
        paused: store.status === 'adjusting',
        found: !!r,
        answer: r?.question.answer ?? '',
        stem: r?.question.stem ?? '',
        options: r?.question.options ?? [],
        bankName: r?.bankName ?? '',
        dark,
      })
    } catch { /* overlay gone */ }
  }

  async function startMobile(floating = false) {
    const { invoke, Channel } = await import('@tauri-apps/api/core')

    const ocrReady = preloadOcr().catch((e) => {
      store.ocrError = e instanceof Error ? e.message : String(e)
      throw e
    })
    if (floating) await ocrReady
    else void ocrReady.catch(() => {})

    const chan = new Channel<Record<string, unknown>>()
    chan.onmessage = (msg) => {
      if (msg?.type !== 'frame') log(`通道消息: ${String(msg?.type)}`)
      switch (msg?.type) {
        case 'begin':
          store.beginRecording()
          void pushAnswerToOverlay()
          break
        case 'frame':
          void processFrameMobile(`data:image/jpeg;base64,${String(msg.data ?? '')}`)
          break
        case 'adjust':
          store.pauseToAdjust()
          void pushAnswerToOverlay()
          break
        case 'exit':
        case 'denied':
          store.stopRecording()
          break
      }
    }

    await invoke(
      floating ? 'plugin:screenrecord|start_float_session' : 'plugin:screenrecord|start_session',
      { events: chan },
    )
    await pushAnswerToOverlay()
  }

  // ===== Main window side =====
  async function start() {
    if (isAndroid()) {
      await startMobile()
      return
    }

    if (isMacOS()) {
      let granted = await api.checkScreenPermission()
      if (!granted) granted = await api.requestScreenPermission()
      if (!granted) {
        await api.openScreenRecordingSettings().catch(() => {})
        throw new Error('screen_permission_required')
      }
    }

    await api.createRecordWindows()
  }

  async function startFloating() {
    if (!isAndroid()) throw new Error('floating_search_android_only')
    await startMobile(true)
  }

  // ===== Answer-float window side (capture loop lives here: this window is
  // always visible, so WebKit never throttles or kills canvas/WASM work) =====

  async function initFloat() {
    const { listen } = await import('@tauri-apps/api/event')

    for (const fn of floatUnlistenFns) fn()
    floatUnlistenFns = []

    preloadOcr().catch((e) => {
      store.ocrError = e instanceof Error ? e.message : String(e)
    })

    floatUnlistenFns.push(await listen<ScreenRegion>('screen-record:begin', (e) => {
      if (e.payload) store.setRegion(e.payload)
      store.beginRecording()
      lastFrameAt = 0
      startTimer()
      void capture()
    }))

    floatUnlistenFns.push(await listen('screen-record:adjust', () => {
      stopTimer()
      store.pauseToAdjust()
    }))
  }

  async function adjust() {
    stopTimer()
    store.pauseToAdjust()
    const { emit } = await import('@tauri-apps/api/event')
    await emit('screen-record:adjust')
  }

  async function stop() {
    stopTimer()
    store.stopRecording()
    for (const fn of floatUnlistenFns) fn()
    floatUnlistenFns = []
    if (isAndroid()) {
      try {
        const { invoke } = await import('@tauri-apps/api/core')
        await invoke('plugin:screenrecord|stop')
      } catch { /* ignore */ }
      return
    }
    try {
      await api.closeRecordWindows()
    } catch { /* ignore */ }
  }

  return { start, startFloating, initFloat, adjust, stop }
}
