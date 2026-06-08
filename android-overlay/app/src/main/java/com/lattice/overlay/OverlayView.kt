package com.lattice.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 229, 57, 53)
        style = Paint.Style.FILL
    }

    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    data class Marker(
        val xPx: Float,
        val yPx: Float,
        val label: String,
        val radiusPx: Float = 24f
    )

    private val markers = mutableListOf<Marker>()

    fun setMarkers(newMarkers: List<Marker>) {
        markers.clear()
        markers.addAll(newMarkers)
        invalidate()
    }

    fun clearMarkers() {
        markers.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val armLen = 30f
        canvas.drawLine(cx - armLen, cy, cx + armLen, cy, crosshairPaint)
        canvas.drawLine(cx, cy - armLen, cx, cy + armLen, crosshairPaint)

        if (markers.isEmpty()) {
            canvas.drawCircle(cx, cy, 30f, markerPaint)
            canvas.drawCircle(cx, cy, 30f, markerStrokePaint)
        } else {
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 32f
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            for (m in markers) {
                canvas.drawCircle(m.xPx, m.yPx, m.radiusPx, markerPaint)
                canvas.drawCircle(m.xPx, m.yPx, m.radiusPx, markerStrokePaint)
                canvas.drawText(m.label, m.xPx + m.radiusPx + 8f, m.yPx + 12f, labelPaint)
            }
        }
    }
}
