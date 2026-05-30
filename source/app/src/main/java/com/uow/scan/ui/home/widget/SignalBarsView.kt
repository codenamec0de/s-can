package com.uow.scan.ui.home.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Four-bar Wi-Fi signal indicator for the nearby-networks list (S'CAN V4).
 * Bar heights step up 0.38 → 0.995 of the view height (mirrors the design's
 * 0.38 + i*0.205). Colour + active-bar count are set per row via [set].
 */
class SignalBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    private var activeBars = 0
    private var activeColor = 0xFFFFFFFF.toInt()
    private var dimColor = 0x33FFFFFF

    private val barCount = 4
    private val barWidthDp = 3.5f
    private val gapDp = 2.5f
    private val radiusDp = 1.5f
    private val factors = floatArrayOf(0.38f, 0.585f, 0.79f, 0.995f)

    fun set(active: Int, activeColor: Int, dimColor: Int) {
        this.activeBars = active.coerceIn(0, barCount)
        this.activeColor = activeColor
        this.dimColor = dimColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val barW = barWidthDp * density
        val gap = gapDp * density
        val radius = radiusDp * density
        val totalW = barCount * barW + (barCount - 1) * gap
        var x = (width - totalW) / 2f
        val bottom = height.toFloat()
        for (i in 0 until barCount) {
            val h = height * factors[i]
            rect.set(x, bottom - h, x + barW, bottom)
            paint.color = if (i < activeBars) activeColor else dimColor
            canvas.drawRoundRect(rect, radius, radius, paint)
            x += barW + gap
        }
    }
}
