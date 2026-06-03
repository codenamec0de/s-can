package com.uow.scan.ui.home.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Live throughput sparkline (S'CAN V4) — a filled area under a stroked polyline, drawn from a
 * rolling data window. The Network Traffic Monitor pushes a new point every ~850ms via
 * [setData] to animate the live rate; colour follows the posture via [setColor].
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data = floatArrayOf(3f, 5f, 4f, 7f, 6f, 9f, 7f, 11f, 8f, 6f, 9f, 12f, 10f, 8f)

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePath = Path()
    private val areaPath = Path()

    fun setColor(color: Int) {
        linePaint.color = color
        areaPaint.color = color
        areaPaint.alpha = 31 // ~0.12 opacity fill, matching the design
        invalidate()
    }

    /** Replace the data window (≥2 points) and redraw. */
    fun setData(values: FloatArray) {
        if (values.size >= 2) {
            data = values
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || data.size < 2) return

        val max = (data.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val pad = 1.5f * density
        val step = w / (data.size - 1)

        linePath.reset()
        areaPath.reset()
        areaPath.moveTo(0f, h)
        for (i in data.indices) {
            val x = i * step
            val y = h - (data[i] / max) * (h - pad * 2f) - pad
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            areaPath.lineTo(x, y)
        }
        areaPath.lineTo(w, h)
        areaPath.close()

        canvas.drawPath(areaPath, areaPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
