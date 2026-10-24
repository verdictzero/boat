package com.verdictzero.boat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Draws roll and pitch over the statistics window as two line traces. */
class MotionTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var times = FloatArray(0)
    private var roll = FloatArray(0)
    private var pitch = FloatArray(0)
    private var windowSeconds = 30f

    private val density = resources.displayMetrics.density

    private val rollPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.trace_roll)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val pitchPaint = Paint(rollPaint).apply {
        color = ContextCompat.getColor(context, R.color.trace_pitch)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.trace_grid)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.trace_text)
        textSize = 10f * density
    }
    private val path = Path()

    fun setData(secondsAgo: FloatArray, rollDeg: FloatArray, pitchDeg: FloatArray, windowSeconds: Float) {
        times = secondsAgo
        roll = rollDeg
        pitch = pitchDeg
        this.windowSeconds = max(windowSeconds, 5f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = 4f * density
        val midY = h / 2f

        // Scale: at least ±5°, otherwise next multiple of 5 above the largest value.
        var maxAbs = 5f
        for (v in roll) maxAbs = max(maxAbs, abs(v))
        for (v in pitch) maxAbs = max(maxAbs, abs(v))
        val range = ceil(maxAbs / 5f) * 5f

        // Grid: zero line, ± range lines, and a few vertical time ticks.
        canvas.drawLine(0f, midY, w, midY, gridPaint)
        canvas.drawLine(0f, pad, w, pad, gridPaint)
        canvas.drawLine(0f, h - pad, w, h - pad, gridPaint)
        val tickEvery = if (windowSeconds > 60f) 30f else 10f
        var t = tickEvery
        while (t < windowSeconds) {
            val x = w - (t / windowSeconds) * w
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawText("-${t.toInt()}s", x + 2f * density, h - pad - 2f * density, textPaint)
            t += tickEvery
        }
        canvas.drawText("+${range.toInt()}°", 2f * density, pad + textPaint.textSize, textPaint)
        canvas.drawText("-${range.toInt()}°", 2f * density, h - pad - 2f * density, textPaint)

        drawTrace(canvas, roll, rollPaint, w, h, pad, range)
        drawTrace(canvas, pitch, pitchPaint, w, h, pad, range)
    }

    private fun drawTrace(canvas: Canvas, values: FloatArray, paint: Paint, w: Float, h: Float, pad: Float, range: Float) {
        val n = minOf(values.size, times.size)
        if (n < 2) return
        val midY = h / 2f
        val halfH = midY - pad
        path.reset()
        for (i in 0 until n) {
            val x = w + (times[i] / windowSeconds) * w
            val y = midY - (values[i] / range) * halfH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
