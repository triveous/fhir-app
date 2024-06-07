package org.smartregister.fhircore.quest.camerax

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar
import org.smartregister.fhircore.quest.R

class CustomSeekBar(context: Context, attrs: AttributeSet) : AppCompatSeekBar(context, attrs) {

    private val measuringTapeDrawable: Drawable

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.CustomSeekBar)
        measuringTapeDrawable = typedArray.getDrawable(R.styleable.CustomSeekBar_measuringTapeDrawable) ?: throw IllegalArgumentException("Missing measuringTapeDrawable attribute")
        typedArray.recycle()

        // Set progress drawable to transparent
        progressDrawable = context.getDrawable(com.google.android.fhir.datacapture.contrib.views.barcode.R.color.transparent)
        //thumb = context.getDrawable(R.drawable.zoom_indicator_2)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        measuringTapeDrawable.setBounds(0, 0, width - 8, height)
        measuringTapeDrawable.draw(canvas)
    }
}


