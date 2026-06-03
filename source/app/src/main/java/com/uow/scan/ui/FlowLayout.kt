package com.uow.scan.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Minimal left-to-right wrapping container (no flexbox dependency). Children flow along a row and
 * wrap to the next line when they would exceed the available width — used for the strength-meter
 * issue chips, matching the design's `flexWrap` chip row.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : ViewGroup(context, attrs, defStyle) {

    private val gapX = (6 * resources.displayMetrics.density).toInt()
    private val gapY = (6 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = width - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > avail) { x = 0; y += lineH + gapY; lineH = 0 }
            x += c.measuredWidth + gapX
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(width, resolveSize(paddingTop + paddingBottom + y + lineH, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = r - l - paddingLeft - paddingRight
        var x = paddingLeft; var y = paddingTop; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > paddingLeft && x + c.measuredWidth > paddingLeft + avail) {
                x = paddingLeft; y += lineH + gapY; lineH = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gapX
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}
