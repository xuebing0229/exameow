package com.exameow.screenrecord

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

class ScreenRecordService : Service() {
  companion object {
    const val ACTION_START = "com.exameow.screenrecord.START"
    const val ACTION_STOP = "com.exameow.screenrecord.STOP"
    const val EXTRA_RESULT_CODE = "resultCode"
    const val EXTRA_DATA = "data"
    private const val CAPTURE_INTERVAL_MS = 1500L
    private const val MAX_OCR_SIDE = 1280
  }

  private var mediaProjection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  private var screenWidth = 0
  private var screenHeight = 0

  private val handlerThread = HandlerThread("screenrecord").apply { start() }
  private val handler: Handler by lazy { Handler(handlerThread.looper) }

  @Volatile
  private var capturing = false

  private val captureLoop = object : Runnable {
    override fun run() {
      if (!capturing) return
      try {
        grabFrame()
      } catch (e: Exception) {
        Log.e("ScreenRecord", "grabFrame failed", e)
      }
      handler.postDelayed(this, CAPTURE_INTERVAL_MS)
    }
  }

  private var singleShotAttempts = 0
  private val singleShotCapture = object : Runnable {
    override fun run() {
      val captured = try {
        grabFrame()
      } catch (e: Exception) {
        Log.e("ScreenRecord", "single capture failed", e)
        false
      }
      if (captured) {
        return
      }
      if (singleShotAttempts >= 10) {
        ScreenRecordRuntime.overlay?.failFloatingCapture()
        return
      }
      singleShotAttempts += 1
      handler.postDelayed(this, 150L)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> startProjection(intent)
      ACTION_STOP -> tearDown()
    }
    return START_STICKY
  }

  private fun startProjection(intent: Intent) {
    val channelId = "screenrecord"
    if (Build.VERSION.SDK_INT >= 26) {
      val chan = NotificationChannel(channelId, "录屏搜题", NotificationManager.IMPORTANCE_LOW)
      (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
    }
    val notification = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, channelId)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(this)
    }
      .setContentTitle("录屏搜题运行中")
      .setContentText("正在实时识别屏幕上的题目")
      .setSmallIcon(android.R.drawable.ic_menu_camera)
      .build()

    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    } else {
      startForeground(1, notification)
    }

    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    if (Build.VERSION.SDK_INT >= 30) {
      val bounds = wm.maximumWindowMetrics.bounds
      screenWidth = bounds.width()
      screenHeight = bounds.height()
    } else {
      val metrics = DisplayMetrics()
      @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
      screenWidth = metrics.widthPixels
      screenHeight = metrics.heightPixels
    }

    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_OK)
    val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
      intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
    } else {
      @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
    }
    if (data == null) {
      tearDown()
      return
    }

    mediaProjection = mgr.getMediaProjection(resultCode, data)
    if (Build.VERSION.SDK_INT >= 34) {
      mediaProjection?.registerCallback(object : MediaProjection.Callback() {
        override fun onStop() {
          tearDown()
        }
      }, handler)
    }

    imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
    virtualDisplay = mediaProjection?.createVirtualDisplay(
      "screenrecord",
      screenWidth,
      screenHeight,
      resources.displayMetrics.densityDpi,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      imageReader?.surface,
      null,
      handler,
    )

    ScreenRecordRuntime.service = this
  }

  fun startCapture() {
    if (capturing) return
    capturing = true
    handler.post(captureLoop)
  }

  fun stopCapture() {
    capturing = false
    handler.removeCallbacks(captureLoop)
  }

  fun captureOnce() {
    stopCapture()
    handler.removeCallbacks(singleShotCapture)
    singleShotAttempts = 0
    handler.postDelayed(singleShotCapture, 180L)
  }

  private fun grabFrame(): Boolean {
    val reader = imageReader ?: return false
    val image = reader.acquireLatestImage() ?: return false
    try {
      val plane = image.planes[0]
      val buffer = plane.buffer
      val pixelStride = plane.pixelStride
      val rowStride = plane.rowStride
      val rowPadding = rowStride - pixelStride * screenWidth

      var bitmap = Bitmap.createBitmap(
        screenWidth + rowPadding / pixelStride,
        screenHeight,
        Bitmap.Config.ARGB_8888,
      )
      bitmap.copyPixelsFromBuffer(buffer)
      var cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
      bitmap.recycle()

      val region: Rect? = ScreenRecordRuntime.overlay?.frameRect()
      if (region != null) {
        val left = region.left.coerceIn(0, screenWidth - 1)
        val top = region.top.coerceIn(0, screenHeight - 1)
        val w = region.width().coerceIn(1, screenWidth - left)
        val h = region.height().coerceIn(1, screenHeight - top)
        val r = Bitmap.createBitmap(cropped, left, top, w, h)
        cropped.recycle()
        cropped = r
      }

      val scale = minOf(1f, MAX_OCR_SIDE.toFloat() / maxOf(cropped.width, cropped.height))
      val finalBitmap = if (scale < 1f) {
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

      val baos = ByteArrayOutputStream()
      finalBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
      finalBitmap.recycle()
      val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

      ScreenRecordRuntime.send("frame") { put("data", b64) }
      return true
    } finally {
      image.close()
    }
  }

  private fun tearDown() {
    stopCapture()
    handler.removeCallbacks(singleShotCapture)
    virtualDisplay?.release()
    virtualDisplay = null
    imageReader?.close()
    imageReader = null
    try {
      mediaProjection?.stop()
    } catch (e: Exception) {
      Log.w("ScreenRecord", "projection stop failed", e)
    }
    mediaProjection = null
    ScreenRecordRuntime.service = null
    stopForeground(true)
    stopSelf()
  }

  override fun onDestroy() {
    tearDown()
    handlerThread.quitSafely()
    super.onDestroy()
  }
}
