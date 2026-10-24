package ru.fortport.client

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Таймлайн плеера: градиент #014CB6 → #619FF2 (одинаковый в дневной и ночной теме),
 * ползунок — audio_timetracker.png.
 */
class GradientSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onScrub(fraction: Float)
        fun onSeekFinished(fraction: Float)
    }

    var listener: Listener? = null

    /** 0..1 */
    var progress: Float = 0f
        set(v) {
            val n = v.coerceIn(0f, 1f)
            if (n != field) { field = n; invalidate() }
        }

    var seekEnabled = false

    private val d = resources.displayMetrics.density
    private val trackH = 6 * d
    private val thumbSize = (22 * d).toInt()
    private val thumb = BitmapFactory.decodeResource(resources, R.drawable.audio_timetracker)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55808A99 }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private var dragging = false

    private fun pad() = thumbSize / 2f
    private fun trackWidth() = (width - 2 * pad()).coerceAtLeast(1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = LinearGradient(
            pad(), 0f, (w - pad()).coerceAtLeast(pad() + 1f), 0f,
            0xFF014CB6.toInt(), 0xFF619FF2.toInt(), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = pad()
        val right = width - pad()
        rect.set(left, cy - trackH / 2, right, cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, trackPaint)
        val x = left + trackWidth() * progress
        rect.set(left, cy - trackH / 2, x.coerceAtLeast(left + trackH), cy + trackH / 2)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, fillPaint)
        val half = thumbSize / 2f
        rect.set(x - half, cy - half, x + half, cy + half)
        canvas.drawBitmap(thumb, null, rect, bmpPaint)
    }

    private fun fractionAt(x: Float) = ((x - pad()) / trackWidth()).coerceIn(0f, 1f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!seekEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                progress = fractionAt(e.x)
                listener?.onScrub(progress)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                progress = fractionAt(e.x)
                listener?.onScrub(progress)
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                progress = fractionAt(e.x)
                listener?.onSeekFinished(progress)
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                listener?.onSeekFinished(progress)
            }
        }
        return true
    }

    val isDragging get() = dragging
}
