package app.tmark.example

import android.content.Context
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import app.tmark.Math
import app.tmark.MathBlock
import app.tmark.android.TmarkWidgets
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import io.ratex.compose.RaTeX

/** Optional RaTeX renderer for both math node types. */
val RaTeXWidgets: TmarkWidgets = TmarkWidgets.build {
    inline(Math) { context, node, owner -> formula(context, node.expression, false, owner) }
    block(MathBlock) { context, node ->
        HorizontalScrollView(context).apply {
            addView(formula(context, node.expression, true, null))
        }
    }
}

private fun formula(context: Context, expression: String, displayMode: Boolean, owner: TextView?): ComposeView =
    ComposeView(context).apply {
        contentDescription = expression
        setContent {
            val size = with(LocalDensity.current) { (owner?.textSize ?: 28f * density).toSp() }
            RaTeX(
                latex = expression,
                fontSize = size,
                displayMode = displayMode,
                color = Color(owner?.currentTextColor ?: MaterialColors.getColor(
                    context, MaterialR.attr.colorOnSurface, android.graphics.Color.BLACK,
                )),
            )
        }
    }
