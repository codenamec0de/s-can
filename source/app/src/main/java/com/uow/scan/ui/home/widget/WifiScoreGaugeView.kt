package com.uow.scan.ui.home.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.uow.scan.R
import kotlin.math.min

/**
 * Compact circular score gauge for the V4 Wi-Fi Security hero.
 * Minimal — track + progress arc with a soft glow. No ticks, no halo.
 *
 * Colour is set externally via [setScore] (caller passes a colour resource so
 * grade thresholds match WifiSecurityAnalyzer.Grade exactly).
 */
class WifiScoreGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val designSize = 88f
    private val radiusVb = 36f
    private val strokeVb = 5f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.v4_hairline2)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.v4_accent)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.v4_accent)
        alpha = 80
    }

    private var score: Int = 0

    fun setScore(newScore: Int, colorRes: Int) {
        score = newScore.coerceIn(0, 100)
        val color = ContextCompat.getColor(context, colorRes)
        progressPaint.color = color
        glowPaint.color = color
        glowPaint.alpha = 80
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val scale = min(w, h) / designSize
        val r = radiusVb * scale
        val stroke = strokeVb * scale

        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        glowPaint.strokeWidth = stroke + 4f * scale

        canvas.drawCircle(cx, cy, r, trackPaint)

        if (score > 0) {
            val sweep = (score / 100f) * 360f
            val left = cx - r
            val top = cy - r
            val right = cx + r
            val bottom = cy + r
            canvas.save()
            canvas.rotate(-90f, cx, cy)
            canvas.drawArc(left, top, right, bottom, 0f, sweep, false, glowPaint)
            canvas.drawArc(left, top, right, bottom, 0f, sweep, false, progressPaint)
            canvas.restore()
        }
    }
}
