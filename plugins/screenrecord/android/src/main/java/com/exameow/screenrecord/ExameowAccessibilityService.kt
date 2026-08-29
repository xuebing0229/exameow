package com.exameow.screenrecord

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class ExameowAccessibilityService : AccessibilityService() {
  companion object {
    private const val MAX_OCR_SIDE = 1280
    @Volatile private var active: ExameowAccessibilityService? = null

    fun isConnected(): Boolean = active != null

    fun captureOnce(): Boolean {
      val service = active ?: return false
      service.captureSelectedRegion()
      return true
    }
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val screenshotExecutor = Executors.newSingleThreadExecutor()

  override fun onServiceConnected() {
    super.onServiceConnected()
    active = this
    Log.i("ScreenRecord", "accessibility screenshot service connected")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

  override fun onInterrupt() {}

  override fun onDestroy() {
    if (active === this) active = null
    screenshotExecutor.shutdownNow()
    super.onDestroy()
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    if (active === this) active = null
    return super.onUnbind(intent)
  }

  private fun captureSelectedRegion() {
    if (Build.VERSION.SDK_INT < 30) {
      ScreenRecordRuntime.overlay?.failFloatingCapture("当前 Android 版本不支持无障碍截图")
      return
    }

    // Give the selection and answer overlays time to disappear before taking
    // the one-shot screenshot.
    mainHandler.postDelayed({
      takeScreenshot(
        Display.DEFAULT_DISPLAY,
        screenshotExecutor,
        object : TakeScreenshotCallback {
          override fun onSuccess(screenshot: ScreenshotResult) {
            val buffer = screenshot.hardwareBuffer
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
            val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
            wrapped?.recycle()
            buffer.close()
            if (bitmap == null) {
              ScreenRecordRuntime.overlay?.failFloatingCapture("截图转换失败，请重试")
              return
            }
            try {
              sendSelectedRegion(bitmap)
            } catch (e: Exception) {
              if (!bitmap.isRecycled) bitmap.recycle()
              Log.e("ScreenRecord", "accessibility screenshot processing failed", e)
              ScreenRecordRuntime.overlay?.failFloatingCapture("截图处理失败，请重试")
            }
          }

          override fun onFailure(errorCode: Int) {
            Log.e("ScreenRecord", "accessibility screenshot failed: $errorCode")
            val message = if (Build.VERSION.SDK_INT >= 34 &&
              errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
            ) {
              "当前应用禁止截图，无法识别"
            } else {
              "截图失败，请重试（$errorCode）"
            }
            ScreenRecordRuntime.overlay?.failFloatingCapture(message)
          }
        },
      )
    }, 180L)
  }

  private fun sendSelectedRegion(source: Bitmap) {
    var cropped = source
    val region = ScreenRecordRuntime.overlay?.frameRect()
    if (region != null) {
      val left = region.left.coerceIn(0, source.width - 1)
      val top = region.top.coerceIn(0, source.height - 1)
      val width = region.width().coerceIn(1, source.width - left)
      val height = region.height().coerceIn(1, source.height - top)
      cropped = Bitmap.createBitmap(source, left, top, width, height)
      if (cropped !== source) source.recycle()
    }

    val longestSide = maxOf(cropped.width, cropped.height)
    val scale = when {
      longestSide > MAX_OCR_SIDE -> MAX_OCR_SIDE.toFloat() / longestSide
      longestSide < 640 -> minOf(2f, 640f / longestSide)
      else -> 1f
    }
    val finalBitmap = if (scale != 1f) {
      val scaled = Bitmap.createScaledBitmap(
        cropped,
        (cropped.width * scale).toInt().coerceAtLeast(1),
        (cropped.height * scale).toInt().coerceAtLeast(1),
        true,
      )
      cropped.recycle()
      scaled
    } else {
      cropped
    }

    val output = ByteArrayOutputStream()
    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)
    finalBitmap.recycle()
    val jpeg = output.toByteArray()
    ScreenRecordRuntime.lastFrameJpeg = jpeg
    ScreenRecordRuntime.send("frame") {
      put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
    }
  }
}
