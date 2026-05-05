package com.uow.scan.ui.home.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.uow.scan.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Hero security-score ring. Mirrors the Home.html design:
 *   - ink-3 bg track (r=86)
 *   - coloured progress arc with glow (cubic-bezier reveal)
 *   - decorative outer ring + breathing halo
 *   - tick marks around the outside (every 1/60th, every 5th thicker)
 *
 * Colour of the progress/halo derives from the score:
 *   score ≥ 75  → ok (green)
 *   score ≥ 45  → warn (amber)
 *   else        → bad (red)
 */
class SecurityScoreRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val designSize = 220f
    private val trackRadiusVb = 86f
    private val strokeWidthVb = 6f
    private val outerRingRadiusVb = 104f
    private val haloRadiusVb = 96f
    private val tickOuterVb = 100f
    private val tickInnerShortVb = 98f
    private val tickInnerLongVb = 95f

    private val inkTrack = ContextCompat.getColor(context, R.color.ink_3)
    private val fgTickColor = ContextCompat.getColor(context, R.color.fg_3)
    private val blue = ContextCompat.getColor(context, R.color.scan_accent)
    private val ok = ContextCompat.getColor(context, R.color.scan_ok)
    private val warn = ContextCompat.getColor(context, R.color.scan_warn)
    private val bad = ContextCompat.getColor(context, R.color.scan_bad)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = inkTrack
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ok
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ok
        alpha = 90
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = inkTrack
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = blue
        alpha = 64 // 0.25 * 255
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgTickColor
        alpha = 77 // 0.3 * 255
        strokeCap = Paint.Cap.SQUARE
    }

    private var score: Int = 0
    private var progressFraction: Float = 0f
    private var haloScale: Float = 1f
    private var haloAlpha: Float = 0.6f

    private var progressAnimator: ValueAnimator? = null
    private val haloAnimator: ValueAnimator

    private val easeOutQuint = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    init {
        haloAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4500
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                // 0..1..0 triangle mapped to scale 1..1.04..1 and alpha 0.6..0.9..0.6
                val t = it.animatedValue as Float
                val triangle = if (t < 0.5f) t * 2f else (1f - t) * 2f
                haloScale = 1f + 0.04f * triangle
                haloAlpha = 0.6f + 0.3f * triangle
                invalidate()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        haloAnimator.start()
    }

    override fun onDetachedFromWindow() {
        haloAnimator.cancel()
        progressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Set the score 0..100 and animate the progress arc.
     * Colour is derived automatically from the score.
     */
    fun setScore(newScore: Int, animate: Boolean = true) {
        val clamped = newScore.coerceIn(0, 100)
        score = clamped
        val target = clamped / 100f
        val color = colorForScore(clamped)
        progressPaint.color = color
        glowPaint.color = color
        glowPaint.alpha = 90
        haloPaint.color = color
        haloPaint.alpha = 64

        progressAnimator?.cancel()
        if (!animate) {
            progressFraction = target
            invalidate()
            return
        }
        val from = progressFraction
        progressAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = 1200
            interpolator = easeOutQuint
            addUpdateListener {
                progressFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Sweep the arc back and forth while a scan is running.
     * Call [setScore] afterwards to settle back to the real value.
     */
    fun startScanSweep() {
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                progressFraction = it.animatedValue as Float
                invalidate()
            }
            progressPaint.color = blue
            glowPaint.color = blue
            glowPaint.alpha = 90
            start()
        }
    }

    fun stopScanSweep() {
        progressAnimator?.cancel()
    }

    private fun colorForScore(s: Int): Int = when {
        s >= 75 -> ok
        s >= 45 -> warn
        else -> bad
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val scale = min(w, h) / designSize

        val rTrack = trackRadiusVb * scale
        val stroke = strokeWidthVb * scale
        val rOuter = outerRingRadiusVb * scale
        val rHalo = haloRadiusVb * scale
        val tickOuter = tickOuterVb * scale
        val tickShort = tickInnerShortVb * scale
        val tickLong = tickInnerLongVb * scale

        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        glowPaint.strokeWidth = stroke + 4f * scale

        // Outer decorative ring (static)
        canvas.drawCircle(cx, cy, rOuter, outerRingPaint)

        // Breathing halo
        haloPaint.alpha = (haloAlpha * 160).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, rHalo * haloScale, haloPaint)

        // Tick marks around the outside
        for (i in 0 until 60) {
            val angle = (i / 60.0) * 2.0 * Math.PI - Math.PI / 2.0
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val x1 = cx + cosA * tickOuter
            val y1 = cy + sinA * tickOuter
            val isMajor = i % 5 == 0
            val inner = if (isMajor) tickLong else tickShort
            val x2 = cx + cosA * inner
            val y2 = cy + sinA * inner
            tickPaint.strokeWidth = (if (isMajor) 1.2f else 0.6f) * scale
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // BG track
        canvas.drawCircle(cx, cy, rTrack, trackPaint)

        // Progress arc + glow. Start from top, sweep clockwise.
        if (progressFraction > 0f) {
            val sweep = progressFraction * 360f
            val left = cx - rTrack
            val top = cy - rTrack
            val right = cx + rTrack
            val bottom = cy + rTrack
            canvas.save()
            canvas.rotate(-90f, cx, cy)
            // Glow (drawn first, larger stroke)
            canvas.drawArc(left, top, right, bottom, 0f, sweep, false, glowPaint)
            canvas.drawArc(left, top, right, bottom, 0f, sweep, false, progressPaint)
            canvas.restore()
        }
    }
}
