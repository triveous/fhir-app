package org.smartregister.fhircore.quest.camerax

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
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
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        measuringTapeDrawable.setBounds(0, 0, width, height)
        measuringTapeDrawable.draw(canvas)
    }
}
