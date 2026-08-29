package com.exameow.screenrecord

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class OverlayController(
  private val activity: Activity,
  private val listener: Listener,
) {
  interface Listener {
    fun onBeginClicked()
    fun onAdjustClicked()
    fun onExitClicked()
    fun onFloatBallClicked()
  }

  private val wm = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val screenW: Int
  private val screenH: Int

  private var frameParams: WindowManager.LayoutParams? = null
  private var frameContainer: FrameLayout? = null
  @Volatile private var frameScreenRect: Rect? = null
  private var frameVisible = true

  private var ballParams: WindowManager.LayoutParams? = null
  private var ballView: TextView? = null

  private var answerParams: WindowManager.LayoutParams? = null
  private var answerRoot: LinearLayout? = null
  private var exitConfirmRoot: LinearLayout? = null
  private lateinit var pausedChip: TextView
  private lateinit var answerLabel: TextView
  private lateinit var stemLabel: TextView
  private lateinit var optionsLabel: TextView
  private lateinit var bankLabel: TextView
  private lateinit var emptyLabel: TextView
  private lateinit var previewImage: ImageView
  private lateinit var beginCardButton: TextView
  private lateinit var adjustButton: ImageButton
  private var everBegan = false
  private var floatingMode = false
  private var currentDark = false
  private val overlayPrefs = activity.getSharedPreferences(
    "screenrecord_overlay_display",
    android.content.Context.MODE_PRIVATE,
  )
  private var answerTextScale = overlayPrefs.getFloat(PREF_ANSWER_TEXT_SCALE, 1f)
  private var answerWidthDp = overlayPrefs.getInt(PREF_ANSWER_WIDTH_DP, 280)
  private var titleView: TextView? = null
  private var gripView: View? = null

  private val accent get() = 0xFF0A84FF.toInt()
  private val textPrimary get() = if (currentDark) 0xFFE5E5EA.toInt() else 0xFF1C1C1E.toInt()
  private val textSecondary get() = if (currentDark) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
  private val fill get() = if (currentDark) 0x14EBEBF5.toInt() else 0x143C3C43.toInt()
  private val cardBgColor get() = if (currentDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
  private val gripColor get() = if (currentDark) 0x4DEBEBF5.toInt() else 0x4D3C3C43.toInt()
  private val iconButtonColor get() = if (currentDark) 0xFFE5E5EA.toInt() else TEXT_PRIMARY
  private val chipBgColor get() = if (currentDark) 0x33FF9500.toInt() else 0x1FFF9500
  private val chipTextColor get() = if (currentDark) 0xFFFFA500.toInt() else 0xFFFF9500.toInt()
  private val answerBgColor get() = if (currentDark) 0x330A84FF.toInt() else 0x1F0A84FF

  companion object {
    private const val ACCENT_CONST = 0xFF0A84FF.toInt()
    private const val TEXT_PRIMARY = 0xFF1C1C1E.toInt()
    private const val TEXT_SECONDARY = 0x993C3C43.toInt()
    private const val FILL = 0x143C3C43
    private const val DANGER = 0xFFFF3B30.toInt()
    private const val PREF_ANSWER_TEXT_SCALE = "answer_text_scale"
    private const val PREF_ANSWER_WIDTH_DP = "answer_width_dp"
  }

  init {
    if (Build.VERSION.SDK_INT >= 30) {
      val b = wm.maximumWindowMetrics.bounds
      screenW = b.width()
      screenH = b.height()
    } else {
      val m = DisplayMetrics()
      @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
      screenW = m.widthPixels
      screenH = m.heightPixels
    }
  }

  private fun dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt()

  private fun overlayParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
    val p = WindowManager.LayoutParams(
      w, h,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      android.graphics.PixelFormat.TRANSLUCENT,
    )
    p.gravity = Gravity.TOP or Gravity.START
    p.x = x
    p.y = y
    return p
  }

  fun showInitial() {
    mainHandler.post {
      try {
        showFrameOverlay()
      } catch (e: Exception) {
        android.util.Log.e("ScreenRecord", "showFrameOverlay failed", e)
      }
      try {
        showAnswerOverlay()
      } catch (e: Exception) {
        android.util.Log.e("ScreenRecord", "showAnswerOverlay failed", e)
      }
    }
  }

  fun showFloatingMode() {
    floatingMode = true
    mainHandler.post { showFloatBall() }
  }

  fun prepareFloatingCapture() {
    floatingMode = true
    everBegan = false
    mainHandler.post {
      hideFloatBall()
      if (frameContainer == null) showFrameOverlay() else frameContainer?.visibility = View.VISIBLE
      if (answerRoot == null) showAnswerOverlay() else answerRoot?.visibility = View.VISIBLE
      titleView?.text = "悬浮窗搜题"
      beginCardButton.text = "\u25B6 识别一次"
      beginCardButton.visibility = View.VISIBLE
      pausedChip.text = "调整截图框，框住题目区域"
      pausedChip.visibility = View.VISIBLE
      emptyLabel.visibility = View.GONE
      answerLabel.visibility = View.GONE
      stemLabel.visibility = View.GONE
      optionsLabel.visibility = View.GONE
      bankLabel.visibility = View.GONE
    }
  }

  fun finishFloatingCapture() {
    if (!floatingMode) return
    mainHandler.post {
      answerRoot?.visibility = View.VISIBLE
      showFloatBall()
    }
  }

  fun failFloatingCapture() {
    if (!floatingMode) return
    mainHandler.post {
      emptyLabel.text = "截图失败，请重试"
      emptyLabel.visibility = View.VISIBLE
      answerRoot?.visibility = View.VISIBLE
      showFloatBall()
    }
  }

  fun markBegan() {
    everBegan = true
    mainHandler.post {
      if (::beginCardButton.isInitialized) {
        beginCardButton.visibility = View.GONE
      }
      if (floatingMode && ::emptyLabel.isInitialized) {
        emptyLabel.text = "识别中…"
        emptyLabel.visibility = View.VISIBLE
      }
    }
  }

  @Suppress("UNUSED_PARAMETER")
  fun setAdjusting(adjusting: Boolean) {
    // No-op: the begin button now lives in the answer card, not the frame.
  }

  fun frameRect(): Rect? {
    frameScreenRect?.let { return Rect(it) }
    val p = frameParams ?: return null
    return Rect(p.x, p.y, p.x + p.width, p.y + p.height)
  }

  private fun updateFrameScreenRect() {
    val view = frameContainer ?: return
    if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    frameScreenRect = Rect(
      location[0],
      location[1],
      location[0] + view.width,
      location[1] + view.height,
    )
  }

  private fun isMostlyBlack(bitmap: android.graphics.Bitmap): Boolean {
    var brightness = 0L
    var samples = 0
    val stepX = max(1, bitmap.width / 12)
    val stepY = max(1, bitmap.height / 8)
    var y = stepY / 2
    while (y < bitmap.height) {
      var x = stepX / 2
      while (x < bitmap.width) {
        val pixel = bitmap.getPixel(x, y)
        brightness += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        samples++
        x += stepX
      }
      y += stepY
    }
    return samples > 0 && brightness / samples < 18
  }

  fun hideFrame() {
    frameVisible = false
    mainHandler.post {
      updateFrameScreenRect()
      frameContainer?.visibility = View.GONE
    }
  }

  fun hideAnswer() {
    mainHandler.post { answerRoot?.visibility = View.GONE }
  }

  fun showFrame() {
    frameVisible = true
    mainHandler.post { frameContainer?.visibility = View.VISIBLE }
  }

  fun removeAll() {
    mainHandler.post {
      frameContainer?.let {
        try { wm.removeView(it) } catch (_: Exception) {}
      }
      frameContainer = null
      frameParams = null
      frameScreenRect = null
      answerRoot?.let {
        try { wm.removeView(it) } catch (_: Exception) {}
      }
      answerRoot = null
      answerParams = null
      ballView?.let {
        try { wm.removeView(it) } catch (_: Exception) {}
      }
      ballView = null
      ballParams = null
      exitConfirmRoot?.let {
        try { wm.removeView(it) } catch (_: Exception) {}
      }
      exitConfirmRoot = null
    }
  }

  // ==================== 悬浮球 ====================

  private fun showFloatBall() {
    ballView?.let {
      it.visibility = View.VISIBLE
      return
    }

    val size = dp(54f)
    val p = overlayParams(size, size, screenW - size - dp(8f), (screenH * 0.38f).toInt())
    ballParams = p
    val ball = TextView(activity).apply {
      text = "搜"
      gravity = Gravity.CENTER
      setTextColor(Color.WHITE)
      textSize = 18f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      elevation = dp(10f).toFloat()
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(accent)
        setStroke(dp(2f), 0x55FFFFFF)
      }
      setOnTouchListener(FloatBallTouchListener())
    }
    ballView = ball
    wm.addView(ball, p)
  }

  private fun hideFloatBall() {
    ballView?.visibility = View.GONE
  }

  private inner class FloatBallTouchListener : View.OnTouchListener {
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    override fun onTouch(v: View, event: MotionEvent): Boolean {
      val p = ballParams ?: return false
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          startRawX = event.rawX
          startRawY = event.rawY
          startX = p.x
          startY = p.y
          moved = false
          return true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - startRawX).toInt()
          val dy = (event.rawY - startRawY).toInt()
          if (kotlin.math.abs(dx) > dp(5f) || kotlin.math.abs(dy) > dp(5f)) moved = true
          p.x = (startX + dx).coerceIn(0, screenW - p.width)
          p.y = (startY + dy).coerceIn(0, screenH - p.height)
          ballView?.let { wm.updateViewLayout(it, p) }
          return true
        }
        MotionEvent.ACTION_UP -> {
          if (moved) {
            p.x = if (p.x + p.width / 2 < screenW / 2) dp(8f) else screenW - p.width - dp(8f)
            ballView?.let { wm.updateViewLayout(it, p) }
          } else {
            listener.onFloatBallClicked()
          }
          return true
        }
      }
      return false
    }
  }

  // ==================== 录制框 ====================

  private inner class FrameBorderView : View(activity) {
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.STROKE
      strokeWidth = dp(2.5f).toFloat()
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0x66000000
      style = Paint.Style.STROKE
      strokeWidth = dp(4.5f).toFloat()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.FILL
    }
    private val gripStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0x66000000
      style = Paint.Style.STROKE
      strokeWidth = dp(1f).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val inset = dp(2.5f).toFloat()
      val rect = RectF(inset, inset, width - inset, height - inset)
      val radius = dp(14f).toFloat()
      canvas.drawRoundRect(rect, radius, radius, outlinePaint)
      canvas.drawRoundRect(rect, radius, radius, borderPaint)

      val dotR = dp(6f).toFloat()
      val gripInset = dp(4f).toFloat()
      val corners = arrayOf(
        gripInset to gripInset,
        width - gripInset to gripInset,
        gripInset to height - gripInset,
        width - gripInset to height - gripInset,
      )
      for ((cx, cy) in corners) {
        canvas.drawCircle(cx, cy, dotR, fillPaint)
        canvas.drawCircle(cx, cy, dotR, gripStroke)
      }

      val gripW = dp(28f).toFloat()
      val gripH = dp(4f).toFloat()
      val cx = width / 2f
      val cy = height / 2f
      val grips = arrayOf(
        RectF(cx - gripW / 2, gripInset + dotR, cx + gripW / 2, gripInset + dotR + gripH),
        RectF(cx - gripW / 2, height - gripInset - dotR - gripH, cx + gripW / 2, height - gripInset - dotR),
        RectF(gripInset + dotR, cy - gripW / 2, gripInset + dotR + gripH, cy + gripW / 2),
        RectF(width - gripInset - dotR - gripH, cy - gripW / 2, width - gripInset - dotR, cy + gripW / 2),
      )
      for (g in grips) {
        canvas.drawRoundRect(g, gripH, gripH, fillPaint)
        canvas.drawRoundRect(g, gripH, gripH, gripStroke)
      }
    }
  }

  private fun showFrameOverlay() {
    val w = (screenW * 0.82f).toInt()
    val h = (screenH * 0.30f).toInt()
    val p = overlayParams(w, h, (screenW - w) / 2, (screenH * 0.16f).toInt())
    frameParams = p

    val container = FrameLayout(activity)
    val border = FrameBorderView()
    container.addView(border, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    val moveListener = FrameTouchListener(moveOnly = true)
    val hint = TextView(activity).apply {
      text = "按住此处移动，拖动边缘缩放"
      setTextColor(Color.WHITE)
      textSize = 11f
      setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
      background = GradientDrawable().apply {
        setColor(0x9E000000.toInt())
        cornerRadius = dp(14f).toFloat()
      }
      setOnTouchListener(moveListener)
    }
    val controls = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      addView(hint)
      setOnTouchListener(moveListener)
    }
    val cp = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    cp.gravity = Gravity.CENTER
    container.addView(controls, cp)

    border.setOnTouchListener(FrameTouchListener())
    frameContainer = container
    wm.addView(container, p)
    container.post { updateFrameScreenRect() }
  }

  private inner class FrameTouchListener(
    private val moveOnly: Boolean = false,
  ) : View.OnTouchListener {
    private var mode = 0 // 0=move, 1=n,2=s,4=w,8=e bitmask resize
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0

    override fun onTouch(v: View, event: MotionEvent): Boolean {
      val p = frameParams ?: return false
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          val edge = dp(20f)
          val corner = dp(40f)
          val x = event.x
          val y = event.y
          mode = 0
          if (!moveOnly) {
            if (y < corner && x < corner) mode = 1 or 4
            else if (y < corner && x > v.width - corner) mode = 1 or 8
            else if (y > v.height - corner && x < corner) mode = 2 or 4
            else if (y > v.height - corner && x > v.width - corner) mode = 2 or 8
            else if (y < edge) mode = 1
            else if (y > v.height - edge) mode = 2
            else if (x < edge) mode = 4
            else if (x > v.width - edge) mode = 8
          }
          startRawX = event.rawX
          startRawY = event.rawY
          startX = p.x
          startY = p.y
          startW = p.width
          startH = p.height
          return true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = (event.rawX - startRawX).toInt()
          val dy = (event.rawY - startRawY).toInt()
          val minW = dp(96f)
          val minH = dp(56f)
          if (mode == 0) {
            p.x = (startX + dx).coerceIn(-startW / 2, screenW - startW / 2)
            p.y = (startY + dy).coerceIn(0, screenH - dp(48f))
          } else {
            var newX = startX
            var newY = startY
            var newW = startW
            var newH = startH
            if (mode and 8 != 0) newW = max(minW, startW + dx)
            if (mode and 2 != 0) newH = max(minH, startH + dy)
            if (mode and 4 != 0) {
              newW = max(minW, startW - dx)
              newX = startX + startW - newW
            }
            if (mode and 1 != 0) {
              newH = max(minH, startH - dy)
              newY = startY + startH - newH
            }
            p.x = newX
            p.y = newY
            p.width = min(newW, screenW)
            p.height = min(newH, screenH)
          }
          frameContainer?.let {
            wm.updateViewLayout(it, p)
            it.post { updateFrameScreenRect() }
          }
          return true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          updateFrameScreenRect()
          return true
        }
      }
      return false
    }
  }

  // ==================== 答案卡片 ====================

  private fun showAnswerOverlay() {
    val width = min(dp(answerWidthDp.toFloat()), screenW - dp(10f))
    val p = overlayParams(width, WRAP_CONTENT, screenW - width - dp(6f), (screenH * 0.5f).toInt())
    answerParams = p

    val cardBg = GradientDrawable().apply {
      setColor(cardBgColor)
      cornerRadius = dp(20f).toFloat()
    }

    val root = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(8f), dp(6f), dp(8f), dp(8f))
      background = cardBg
      elevation = dp(10f).toFloat()
    }

    gripView = View(activity).apply {
      background = GradientDrawable().apply {
        setColor(gripColor)
        cornerRadius = dp(2.5f).toFloat()
      }
    }
    val gripLp = LinearLayout.LayoutParams(dp(36f), dp(5f))
    gripLp.gravity = Gravity.CENTER_HORIZONTAL
    gripLp.bottomMargin = dp(4f)
    root.addView(gripView!!, gripLp)

    val header = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    titleView = TextView(activity).apply {
      text = if (floatingMode) "悬浮窗搜题" else "录屏搜题"
      setTextColor(textPrimary)
      textSize = 10f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    header.addView(titleView!!, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    header.addView(iconButton(R.drawable.ic_adjust) { listener.onAdjustClicked() }.also { adjustButton = it })
    header.addView(iconButton(R.drawable.ic_close, DANGER) { showExitConfirmation() })
    root.addView(header)

    beginCardButton = TextView(activity).apply {
      text = if (floatingMode) "\u25B6 识别一次" else "\u25B6 开始录制"
      setTextColor(Color.WHITE)
      textSize = 11f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
      background = GradientDrawable().apply {
        setColor(accent)
        cornerRadius = dp(24f).toFloat()
      }
      setOnClickListener { listener.onBeginClicked() }
    }
    val beginLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
      topMargin = dp(8f)
      bottomMargin = dp(8f)
      leftMargin = dp(6f)
      rightMargin = dp(6f)
    }
    root.addView(beginCardButton, beginLp)

    pausedChip = TextView(activity).apply {
      text = "调整录制框中，识别已暂停"
      setTextColor(chipTextColor)
      textSize = 10f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
      background = GradientDrawable().apply {
        setColor(chipBgColor)
        cornerRadius = dp(12f).toFloat()
      }
      visibility = View.GONE
    }
    val chipLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    chipLp.topMargin = dp(4f)
    root.addView(pausedChip, chipLp)

    answerLabel = TextView(activity).apply {
      setTextColor(accent)
      textSize = 15f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
      background = GradientDrawable().apply {
        setColor(answerBgColor)
        cornerRadius = dp(14f).toFloat()
      }
      visibility = View.GONE
    }
    val answerLp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    answerLp.topMargin = dp(6f)
    root.addView(answerLabel, answerLp)

    stemLabel = TextView(activity).apply {
      setTextColor(textPrimary)
      textSize = 10f
      maxLines = 3
      ellipsize = android.text.TextUtils.TruncateAt.END
      visibility = View.GONE
    }
    val stemLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    stemLp.topMargin = dp(4f)
    root.addView(stemLabel, stemLp)

    optionsLabel = TextView(activity).apply {
      setTextColor(textSecondary)
      textSize = 9f
      maxLines = 6
      ellipsize = android.text.TextUtils.TruncateAt.END
      visibility = View.GONE
    }
    val optLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    optLp.topMargin = dp(4f)
    root.addView(optionsLabel, optLp)

    bankLabel = TextView(activity).apply {
      setTextColor(textSecondary)
      textSize = 8f
      visibility = View.GONE
    }
    val bankLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    bankLp.topMargin = dp(3f)
    root.addView(bankLabel, bankLp)

    previewImage = ImageView(activity).apply {
      adjustViewBounds = true
      scaleType = ImageView.ScaleType.FIT_CENTER
      visibility = View.GONE
    }
    val previewLp = LinearLayout.LayoutParams(MATCH_PARENT, dp(110f))
    previewLp.topMargin = dp(5f)
    root.addView(previewImage, previewLp)

    emptyLabel = TextView(activity).apply {
      text = "未匹配到题目"
      setTextColor(textSecondary)
      textSize = 10f
      gravity = Gravity.CENTER
      setPadding(0, dp(8f), 0, dp(6f))
    }
    root.addView(emptyLabel)
    applyAnswerTextScale()

    val displayControls = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      setPadding(0, dp(6f), 0, 0)
    }
    displayControls.addView(answerControlButton("窗口 −") { changeAnswerWidth(-40f) })
    displayControls.addView(answerControlButton("窗口 +") { changeAnswerWidth(40f) })
    displayControls.addView(answerControlButton("字号 −") { changeAnswerTextScale(-0.15f) })
    displayControls.addView(answerControlButton("字号 +") { changeAnswerTextScale(0.15f) })
    root.addView(displayControls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    val dragListener = DragTouchListener { p }
    gripView!!.setOnTouchListener(dragListener)
    header.setOnTouchListener(dragListener)

    answerRoot = root
    wm.addView(root, p)
  }

  private fun iconButton(iconRes: Int, color: Int = iconButtonColor, onClick: () -> Unit): ImageButton {
    return ImageButton(activity).apply {
      setImageResource(iconRes)
      setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
      scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
      val padding = dp(4f)
      setPadding(padding, padding, padding, padding)
      val size = dp(24f)
      background = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = size / 2f
      }
      layoutParams = LinearLayout.LayoutParams(size, size).apply {
        leftMargin = dp(4f)
      }
      setOnClickListener { onClick() }
    }
  }

  private fun answerControlButton(label: String, onClick: () -> Unit): TextView {
    return TextView(activity).apply {
      text = label
      setTextColor(accent)
      textSize = 10f
      gravity = Gravity.CENTER
      setPadding(dp(6f), dp(7f), dp(6f), dp(7f))
      background = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(12f).toFloat()
      }
      layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
        leftMargin = dp(2f)
        rightMargin = dp(2f)
      }
      setOnClickListener { onClick() }
    }
  }

  private fun changeAnswerWidth(deltaDp: Float) {
    val p = answerParams ?: return
    val maxWidth = min(dp(380f), screenW - dp(10f))
    p.width = (p.width + dp(deltaDp)).coerceIn(dp(180f), maxWidth)
    p.x = p.x.coerceIn(0, screenW - p.width)
    answerWidthDp = (p.width / activity.resources.displayMetrics.density).toInt()
    overlayPrefs.edit().putInt(PREF_ANSWER_WIDTH_DP, answerWidthDp).apply()
    answerRoot?.let { wm.updateViewLayout(it, p) }
  }

  private fun changeAnswerTextScale(delta: Float) {
    answerTextScale = (answerTextScale + delta).coerceIn(0.75f, 1.9f)
    overlayPrefs.edit().putFloat(PREF_ANSWER_TEXT_SCALE, answerTextScale).apply()
    applyAnswerTextScale()
  }

  private fun applyAnswerTextScale() {
    answerLabel.textSize = 15f * answerTextScale
    stemLabel.textSize = 10f * answerTextScale
    optionsLabel.textSize = 9f * answerTextScale
    bankLabel.textSize = 8f * answerTextScale
    emptyLabel.textSize = 10f * answerTextScale
  }

  private fun showExitConfirmation() {
    mainHandler.post {
      if (exitConfirmRoot != null) return@post

      val width = min(dp(280f), screenW - dp(28f))
      val p = overlayParams(
        width,
        WRAP_CONTENT,
        (screenW - width) / 2,
        (screenH * 0.38f).toInt(),
      )
      val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18f), dp(16f), dp(18f), dp(14f))
        elevation = dp(14f).toFloat()
        background = GradientDrawable().apply {
          setColor(cardBgColor)
          cornerRadius = dp(20f).toFloat()
        }
      }
      root.addView(TextView(activity).apply {
        text = "关闭悬浮搜题？"
        setTextColor(textPrimary)
        textSize = 16f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
      })
      root.addView(TextView(activity).apply {
        text = "关闭后将停止录屏和题目识别。"
        setTextColor(textSecondary)
        textSize = 12f
        setPadding(0, dp(7f), 0, dp(14f))
      })

      val actions = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
      }
      actions.addView(TextView(activity).apply {
        text = "取消"
        setTextColor(accent)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
        setOnClickListener {
          exitConfirmRoot?.let { view -> try { wm.removeView(view) } catch (_: Exception) {} }
          exitConfirmRoot = null
        }
      })
      actions.addView(TextView(activity).apply {
        text = "确认关闭"
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
        background = GradientDrawable().apply {
          setColor(DANGER)
          cornerRadius = dp(18f).toFloat()
        }
        setOnClickListener {
          exitConfirmRoot?.let { view -> try { wm.removeView(view) } catch (_: Exception) {} }
          exitConfirmRoot = null
          listener.onExitClicked()
        }
      })
      root.addView(actions)

      exitConfirmRoot = root
      wm.addView(root, p)
    }
  }

  private inner class DragTouchListener(
    private val paramsProvider: () -> WindowManager.LayoutParams?,
  ) : View.OnTouchListener {
    private var startRawX = 0f
    private var startRawY = 0f
    private var startX = 0
    private var startY = 0

    override fun onTouch(v: View, event: MotionEvent): Boolean {
      val p = paramsProvider() ?: return false
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          startRawX = event.rawX
          startRawY = event.rawY
          startX = p.x
          startY = p.y
          return true
        }
        MotionEvent.ACTION_MOVE -> {
          p.x = (startX + (event.rawX - startRawX).toInt()).coerceIn(0, screenW - dp(48f))
          p.y = (startY + (event.rawY - startRawY).toInt()).coerceIn(0, screenH - dp(48f))
          answerRoot?.let { wm.updateViewLayout(it, p) }
          return true
        }
      }
      return false
    }
  }

  fun setDark(dark: Boolean) {
    if (dark == currentDark) return
    currentDark = dark
    mainHandler.post {
      applyTheme()
    }
  }

  private fun applyTheme() {
    if (answerRoot == null) return
    answerRoot!!.background = GradientDrawable().apply {
      setColor(cardBgColor)
      cornerRadius = dp(20f).toFloat()
    }
    titleView?.setTextColor(textPrimary)
    gripView?.background = GradientDrawable().apply {
      setColor(gripColor)
      cornerRadius = dp(2.5f).toFloat()
    }
    pausedChip.apply {
      setTextColor(chipTextColor)
      background = GradientDrawable().apply {
        setColor(chipBgColor)
        cornerRadius = dp(12f).toFloat()
      }
    }
    answerLabel.apply {
      setTextColor(accent)
      background = GradientDrawable().apply {
        setColor(answerBgColor)
        cornerRadius = dp(14f).toFloat()
      }
    }
    stemLabel.setTextColor(textPrimary)
    optionsLabel.setTextColor(textSecondary)
    bankLabel.setTextColor(textSecondary)
    emptyLabel.setTextColor(textSecondary)
    if (::adjustButton.isInitialized) {
      adjustButton.setColorFilter(iconButtonColor, android.graphics.PorterDuff.Mode.SRC_IN)
    }
  }

  fun updateAnswer(
    paused: Boolean,
    found: Boolean,
    answer: String,
    stem: String,
    options: List<String>,
    bankName: String,
    ocrText: String,
  ) {
    mainHandler.post {
      if (answerRoot == null) return@post
      if (paused || !everBegan) {
        beginCardButton.visibility = View.VISIBLE
        beginCardButton.text = if (floatingMode) {
          "\u25B6 识别一次"
        } else if (everBegan) {
          "\u25B6 继续录制"
        } else {
          "\u25B6 开始录制"
        }
        pausedChip.visibility = if (paused) View.VISIBLE else View.GONE
        emptyLabel.visibility = View.GONE
        previewImage.visibility = View.GONE
        answerLabel.visibility = View.GONE
        stemLabel.visibility = View.GONE
        optionsLabel.visibility = View.GONE
        bankLabel.visibility = View.GONE
        return@post
      }
      beginCardButton.visibility = View.GONE
      pausedChip.visibility = View.GONE
      if (!found) {
        emptyLabel.visibility = View.VISIBLE
        val recognized = ocrText.replace(Regex("\\s+"), " ").trim().take(160)
        val preview = ScreenRecordRuntime.lastFrameJpeg
        val bitmap = preview?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        emptyLabel.text = when {
          bitmap != null && isMostlyBlack(bitmap) ->
            "录屏画面被系统屏蔽\n请重新授权“整个屏幕”后再试"
          recognized.isEmpty() ->
            "未识别到文字\n请扩大框选范围或提高画面清晰度"
          else ->
            "未匹配到题目\n识别文字：$recognized"
        }
        if (bitmap != null) {
          previewImage.setImageBitmap(bitmap)
          previewImage.visibility = View.VISIBLE
        } else {
          previewImage.setImageDrawable(null)
          previewImage.visibility = View.GONE
        }
        answerLabel.visibility = View.GONE
        stemLabel.visibility = View.GONE
        optionsLabel.visibility = View.GONE
        bankLabel.visibility = View.GONE
        return@post
      }

      emptyLabel.visibility = View.GONE
      previewImage.visibility = View.GONE
      answerLabel.visibility = View.VISIBLE
      answerLabel.text = "答案: $answer"
      stemLabel.visibility = View.VISIBLE
      stemLabel.text = stem

      if (options.isNotEmpty()) {
        optionsLabel.visibility = View.VISIBLE
        val correctLetters = answer.uppercase().filter { it in 'A'..'H' }.toSet()
        val sb = StringBuilder()
        options.forEachIndexed { idx, opt ->
          if (idx > 0) sb.append('\n')
          sb.append(('A' + idx)).append(". ").append(opt)
        }
        val spannable = SpannableString(sb.toString())
        var lineStart = 0
        val lines = sb.toString().split('\n')
        for ((idx, line) in lines.withIndex()) {
          val end = lineStart + line.length
          val letter = 'A' + idx
          if (correctLetters.contains(letter)) {
            spannable.setSpan(
              ForegroundColorSpan(accent), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
              StyleSpan(android.graphics.Typeface.BOLD), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
          }
          lineStart = end + 1
        }
        optionsLabel.text = spannable
      } else {
        optionsLabel.visibility = View.GONE
      }

      if (bankName.isNotEmpty()) {
        bankLabel.visibility = View.VISIBLE
        bankLabel.text = bankName
      } else {
        bankLabel.visibility = View.GONE
      }
    }
  }
}
