package com.linkguard.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class QrScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#99000000") // Semi-transparent black
    }

    private val clearPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00E676") // Green color
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val laserPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        alpha = 160
        strokeWidth = 4f
    }

    private val rect = RectF()

    // The scrim + punched hole + corner brackets never change once the view is sized, so
    // pre-render them to a bitmap once. onDraw then blits the cached bitmap and draws only
    // the moving laser line — no per-frame software-layer recomposite.
    private var scrimBitmap: Bitmap? = null
    private var laserTop = 0f
    private var laserBottom = 0f
    private var laserY = 0f

    private val laserAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2500L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val fraction = it.animatedValue as Float
            laserY = laserTop + (laserBottom - laserTop) * fraction
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        buildScrim(w, h)
    }

    private fun buildScrim(w: Int, h: Int) {
        val boxSize = w * 0.75f
        val left = (w - boxSize) / 2
        val top = (h - boxSize) / 2
        val right = left + boxSize
        val bottom = top + boxSize
        rect.set(left, top, right, bottom)

        laserTop = top + 20f
        laserBottom = bottom - 20f
        laserY = laserTop

        scrimBitmap?.recycle()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Dark scrim, then punch a rounded transparent hole in the middle.
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrimPaint)
        canvas.drawRoundRect(rect, 40f, 40f, clearPaint)

        // 2. Static corner brackets.
        val cornerLength = 60f
        val offset = cornerPaint.strokeWidth / 2

        canvas.drawLine(left + offset, top + cornerLength, left + offset, top, cornerPaint)
        canvas.drawLine(left, top + offset, left + cornerLength, top + offset, cornerPaint)
        canvas.drawLine(right - offset, top + cornerLength, right - offset, top, cornerPaint)
        canvas.drawLine(right, top + offset, right - cornerLength, top + offset, cornerPaint)
        canvas.drawLine(left + offset, bottom - cornerLength, left + offset, bottom, cornerPaint)
        canvas.drawLine(left, bottom - offset, left + cornerLength, bottom - offset, cornerPaint)
        canvas.drawLine(right - offset, bottom - cornerLength, right - offset, bottom, cornerPaint)
        canvas.drawLine(right, bottom - offset, right - cornerLength, bottom - offset, cornerPaint)

        scrimBitmap = bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = scrimBitmap ?: return
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.drawLine(rect.left + 20f, laserY, rect.right - 20f, laserY, laserPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!laserAnimator.isStarted) laserAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        laserAnimator.cancel()
        scrimBitmap?.recycle()
        scrimBitmap = null
    }
}
