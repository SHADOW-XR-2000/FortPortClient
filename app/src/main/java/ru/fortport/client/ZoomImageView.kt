package ru.fortport.client

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView с зумом щипком, перетаскиванием увеличенного изображения и
 * двойным тапом (увеличить / вернуть). Без сторонних библиотек.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val tmpRect = RectF()
    private var baseScale = 1f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val target = (current * detector.scaleFactor)
                    .coerceIn(baseScale, baseScale * MAX_ZOOM)
                val factor = target / current
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                fixTranslation()
                imageMatrix = matrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (scaleDetector.isInProgress) return true
                if (currentScale() > baseScale * 1.01f) {
                    matrix.postTranslate(-distanceX, -distanceY)
                    fixTranslation()
                    imageMatrix = matrix
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (drawable == null) return true
                if (currentScale() > baseScale * 1.01f) {
                    resetMatrix()
                } else {
                    val factor = DOUBLE_TAP_ZOOM
                    matrix.postScale(factor, factor, e.x, e.y)
                    fixTranslation()
                    imageMatrix = matrix
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return true
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun currentScale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** Вписываем картинку в экран целиком и центрируем. */
    private fun resetMatrix() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) return

        baseScale = minOf(vw / dw, vh / dh)
        matrix.reset()
        matrix.postScale(baseScale, baseScale)
        matrix.postTranslate((vw - dw * baseScale) / 2f, (vh - dh * baseScale) / 2f)
        imageMatrix = matrix
    }

    /** Не даём увести картинку за границы; если она меньше экрана — центрируем. */
    private fun fixTranslation() {
        val d = drawable ?: return
        tmpRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(tmpRect)
        val vw = width.toFloat()
        val vh = height.toFloat()

        var dx = 0f
        var dy = 0f
        if (tmpRect.width() <= vw) {
            dx = (vw - tmpRect.width()) / 2f - tmpRect.left
        } else if (tmpRect.left > 0f) {
            dx = -tmpRect.left
        } else if (tmpRect.right < vw) {
            dx = vw - tmpRect.right
        }
        if (tmpRect.height() <= vh) {
            dy = (vh - tmpRect.height()) / 2f - tmpRect.top
        } else if (tmpRect.top > 0f) {
            dy = -tmpRect.top
        } else if (tmpRect.bottom < vh) {
            dy = vh - tmpRect.bottom
        }
        matrix.postTranslate(dx, dy)
    }

    private companion object {
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
