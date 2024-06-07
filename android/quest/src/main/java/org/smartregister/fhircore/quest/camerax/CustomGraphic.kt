package org.smartregister.fhircore.quest.camerax


import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
class CustomGraphic @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width
        val height = height
        val thirdWidth = width / 3f
        val thirdHeight = height / 3f

        // Draw vertical lines
        for (i in 1..2) {
            canvas.drawLine(thirdWidth * i, 0f, thirdWidth * i, height.toFloat(), paint)
        }

        // Draw horizontal lines
        for (i in 1..2) {
            canvas.drawLine(0f, thirdHeight * i, width.toFloat(), thirdHeight * i, paint)
        }
    }

}