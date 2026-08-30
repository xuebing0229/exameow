package com.exameow.screenrecord

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
  @Volatile private var captureInFlight = false

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
    if (captureInFlight) {
      Log.i("ScreenRecord", "floating screenshot ignored: capture already in flight")
      return
    }
    captureInFlight = true

    // Give the selection and answer overlays time to disappear before taking
    // the one-shot screenshot.
    mainHandler.postDelayed({
      try {
        takeScreenshot(
          Display.DEFAULT_DISPLAY,
          screenshotExecutor,
          object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
              try {
                sendSelectedRegion(screenshot)
              } catch (e: OutOfMemoryError) {
                Log.e("ScreenRecord", "accessibility screenshot ran out of memory", e)
                ScreenRecordRuntime.lastFrameJpeg = null
                ScreenRecordRuntime.overlay?.failFloatingCapture("截图占用内存过高，请缩小框选范围后重试")
              } catch (e: Exception) {
                Log.e("ScreenRecord", "accessibility screenshot processing failed", e)
                ScreenRecordRuntime.overlay?.failFloatingCapture("截图处理失败，请重试")
              } finally {
                captureInFlight = false
              }
            }

            override fun onFailure(errorCode: Int) {
              captureInFlight = false
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
      } catch (e: Exception) {
        captureInFlight = false
        Log.e("ScreenRecord", "takeScreenshot request failed", e)
        ScreenRecordRuntime.overlay?.failFloatingCapture("截图请求失败，请重试")
      }
    }, 180L)
  }

  private fun sendSelectedRegion(screenshot: ScreenshotResult) {
    val buffer = screenshot.hardwareBuffer
    val source = try {
      Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
    } catch (e: Exception) {
      buffer.close()
      throw e
    }

    if (source == null) {
      buffer.close()
      throw IllegalStateException("Bitmap.wrapHardwareBuffer returned null")
    }

    try {
      val region = ScreenRecordRuntime.overlay?.frameRect()
      val src = if (region != null) {
        val left = region.left.coerceIn(0, source.width - 1)
        val top = region.top.coerceIn(0, source.height - 1)
        val width = region.width().coerceIn(1, source.width - left)
        val height = region.height().coerceIn(1, source.height - top)
        Rect(left, top, left + width, top + height)
      } else {
        Rect(0, 0, source.width, source.height)
      }

      val longestSide = maxOf(src.width(), src.height())
      val scale = when {
        longestSide > MAX_OCR_SIDE -> MAX_OCR_SIDE.toFloat() / longestSide
        longestSide < 640 -> minOf(2f, 640f / longestSide)
        else -> 1f
      }
      val targetWidth = (src.width() * scale).toInt().coerceAtLeast(1)
      val targetHeight = (src.height() * scale).toInt().coerceAtLeast(1)

      // Draw the selected hardware-buffer region directly into the final OCR
      // bitmap. This avoids allocating a full-screen ARGB software copy plus
      // separate crop/scale bitmaps on every floating-search tap.
      val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
      try {
        val canvas = Canvas(finalBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
          source,
          src,
          Rect(0, 0, targetWidth, targetHeight),
          paint,
        )

        val output = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        val jpeg = output.toByteArray()
        ScreenRecordRuntime.lastFrameJpeg = jpeg
        ScreenRecordRuntime.send("frame") {
          put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        }
      } finally {
        finalBitmap.recycle()
      }
    } finally {
      source.recycle()
      buffer.close()
    }
  }
}
