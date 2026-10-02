package app.tmark.android

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Reserves text space while the actual widget draws in the TextView parent's overlay. */
internal class InlineWidgetSpan(
    private val widget: View,
    private val owner: TextView,
    private val offset: Int,
) : ReplacementSpan(), View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private var parent: ViewGroup? = null
    private var width = 0
    private var height = 0

    init {
        owner.addOnAttachStateChangeListener(this)
        if (owner.isAttachedToWindow) attach()
    }

    override fun onViewAttachedToWindow(view: View) = attach()
    override fun onViewDetachedFromWindow(view: View) {
        owner.viewTreeObserver.removeOnPreDrawListener(this)
        parent?.overlay?.remove(widget)
        parent = null
    }

    private fun attach() {
        if (parent != null) return
        val container = owner.parent as? ViewGroup ?: return
        parent = container
        owner.findViewTreeLifecycleOwner()?.let { widget.setViewTreeLifecycleOwner(it) }
        owner.findViewTreeSavedStateRegistryOwner()?.let { widget.setViewTreeSavedStateRegistryOwner(it) }
        container.overlay.add(widget)
        (widget.parent as? View)?.let { overlay ->
            owner.findViewTreeLifecycleOwner()?.let { overlay.setViewTreeLifecycleOwner(it) }
            owner.findViewTreeSavedStateRegistryOwner()?.let { overlay.setViewTreeSavedStateRegistryOwner(it) }
        }
        owner.viewTreeObserver.addOnPreDrawListener(this)
        owner.invalidate()
    }

    override fun onPreDraw(): Boolean {
        if (parent == null) return true
        widget.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val newWidth = widget.measuredWidth
        val newHeight = widget.measuredHeight
        if (width != newWidth || height != newHeight) {
            width = newWidth
            height = newHeight
            owner.post { owner.requestLayout(); owner.invalidate() }
        }
        val layout = owner.layout ?: return true
        val line = layout.getLineForOffset(offset)
        val x = owner.left + owner.totalPaddingLeft - owner.scrollX + layout.getPrimaryHorizontal(offset).roundToInt()
        val baseline = owner.top + owner.totalPaddingTop - owner.scrollY + layout.getLineBaseline(line)
        val y = baseline + owner.paint.fontMetricsInt.descent - height
        widget.layout(x, y, x + width, y + height)
        return true
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val measuredWidth = width.takeIf { it > 0 }
            ?: ceil(paint.measureText(text, start, end).toDouble()).toInt()
        if (fm != null && height > 0) {
            val ascent = -(height - paint.fontMetricsInt.descent).coerceAtLeast(0)
            fm.ascent = minOf(fm.ascent, ascent)
            fm.top = minOf(fm.top, fm.ascent)
        }
        return measuredWidth
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        if (parent == null) canvas.drawText(text, start, end, x, y.toFloat(), paint)
    }
}
