package com.uow.scan.ui.home.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.uow.scan.R

/**
 * Concentric accent "radar" used by the Wi-Fi scanning state (S'CAN V4).
 * Three rings expand (scale 0.35 → 1.0) and fade (opacity 0.9 → 0) on staggered
 * 0.6s phases over a 1.8s loop — the design's `scanPulse` keyframe. The centre
 * disc + glyph are overlaid by the layout; this view draws only the rings.
 */
class RadarPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.v4_accent)
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val ringCount = 3
    private var progress = 0f
    private var animator: ValueAnimator? = null

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f - ringPaint.strokeWidth
        for (i in 0 until ringCount) {
            var p = progress + i.toFloat() / ringCount
            if (p > 1f) p -= 1f
            val scale = 0.35f + 0.65f * p
            val alpha = (0.9f * (1f - p) * 255f).toInt().coerceIn(0, 255)
            ringPaint.alpha = alpha
            canvas.drawCircle(cx, cy, maxR * scale, ringPaint)
        }
    }
}
