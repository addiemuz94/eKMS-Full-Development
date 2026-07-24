package com.ekms.terminal.hardware.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Ported unchanged from `../eKMSHardwareTester`'s `face/FaceDetectionOverlayView.kt`. */
class FaceDetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class DetectedFace(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
    )

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var sourceWidth = 1
    private var sourceHeight = 1
    private var detectedFaces: List<DetectedFace> = emptyList()

    init {
        boxPaint.color = Color.rgb(34, 197, 94)
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = dp(3).toFloat()

        textPaint.color = Color.WHITE
        textPaint.textSize = dp(13).toFloat()
        textPaint.isFakeBoldText = true

        textBackgroundPaint.color = Color.rgb(22, 101, 52)
        textBackgroundPaint.style = Paint.Style.FILL
    }

    fun updateFaces(frameWidth: Int, frameHeight: Int, faces: List<DetectedFace>) {
        post {
            sourceWidth = max(1, frameWidth)
            sourceHeight = max(1, frameHeight)
            detectedFaces = faces
            invalidate()
        }
    }

    fun clearFaces() {
        post {
            detectedFaces = emptyList()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val scaleX = width.toFloat() / sourceWidth.toFloat()
        val scaleY = height.toFloat() / sourceHeight.toFloat()

        detectedFaces.forEach { face ->
            val left = face.left * scaleX
            val top = face.top * scaleY
            val right = face.right * scaleX
            val bottom = face.bottom * scaleY

            val rectangle = RectF(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
            canvas.drawRoundRect(rectangle, dp(8).toFloat(), dp(8).toFloat(), boxPaint)

            val label = "Face ${(face.confidence * 100f).toInt()}%"
            val labelWidth = textPaint.measureText(label) + dp(16)
            val labelHeight = dp(28).toFloat()
            val labelTop = max(0f, rectangle.top - labelHeight)
            val labelRight = min(width.toFloat(), rectangle.left + labelWidth)

            canvas.drawRoundRect(
                RectF(rectangle.left, labelTop, labelRight, labelTop + labelHeight),
                dp(6).toFloat(),
                dp(6).toFloat(),
                textBackgroundPaint,
            )
            canvas.drawText(label, rectangle.left + dp(8), labelTop + dp(19), textPaint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
