package dev.anonymous.cardsdesignerpro.app.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import dev.anonymous.cardsdesignerpro.app.R
import dev.anonymous.cardsdesignerpro.app.databinding.WidgetStepperBinding

/**
 * Reusable stepper widget: [ − | value | + ]
 * Used for digit count, font size, frame thickness, etc.
 */
class StepperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding = WidgetStepperBinding.inflate(LayoutInflater.from(context), this, true)

    var minValue: Int = 1
    var maxValue: Int = 100
    var value: Int = 1
        set(v) {
            val clamped = v.coerceIn(minValue, maxValue)
            if (field == clamped) return           // ← skip if unchanged
            field = clamped
            binding.tvValue.text = field.toString()
            onValueChanged?.invoke(field)
        }

    var onValueChanged: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        binding.btnDecrement.setOnClickListener { value-- }
        binding.btnIncrement.setOnClickListener { value++ }
        binding.tvValue.text = value.toString()
    }
}
