package io.github.markspan.polarmobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors
import io.github.markspan.polarmobile.stream.PreviewBuffer

/** Auto-scaling live plot of a [PreviewBuffer]; one trace per channel, redrawn each frame while attached. */
class SignalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var buffer: PreviewBuffer? = null
    private var scratch = FloatArray(0)
    private val path = Path()
    private val traceColors = intArrayOf(
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary),
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SignalView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    var label: String = ""

    fun bind(buffer: PreviewBuffer, label: String) {
        this.buffer = buffer
        this.label = label
        scratch = FloatArray(buffer.channels * buffer.capacity)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val buf = buffer ?: return
        val n = buf.snapshot(scratch)
        val ch = buf.channels
        canvas.drawText(label, 4f, labelPaint.textSize, labelPaint)
        if (n > 1) {
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (i in 0 until n * ch) {
                min = minOf(min, scratch[i]); max = maxOf(max, scratch[i])
            }
            val range = (max - min).takeIf { it > 0f } ?: 1f
            val top = labelPaint.textSize * 1.4f
            val h = height - top - paddingBottom
            val dx = width.toFloat() / (buf.capacity - 1)
            for (c in 0 until ch) {
                path.reset()
                for (s in 0 until n) {
                    val x = s * dx
                    val y = top + h * (1f - (scratch[s * ch + c] - min) / range)
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.color = traceColors[c % traceColors.size]
                canvas.drawPath(path, paint)
            }
        }
        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
