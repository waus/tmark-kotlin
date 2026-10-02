package app.tmark.android

import android.content.Context
import android.view.View
import android.widget.TextView
import app.tmark.BlockNode
import app.tmark.NodeType
import app.tmark.RichNode
import kotlin.reflect.KClass

/** Type-indexed render hooks. Inline widgets receive the owning TextView for size and color. */
class TmarkWidgets private constructor(
    private val blocks: Map<KClass<*>, (Context, BlockNode) -> View>,
    private val inlines: Map<KClass<*>, (Context, RichNode, TextView) -> View>,
) {
    fun block(context: Context, node: BlockNode): View? = blocks[node::class]?.invoke(context, node)
    fun inline(context: Context, node: RichNode, owner: TextView): View? =
        inlines[node::class]?.invoke(context, node, owner)

    /** Combine independent extensions; handlers from [other] take precedence. */
    operator fun plus(other: TmarkWidgets) = TmarkWidgets(blocks + other.blocks, inlines + other.inlines)

    class Builder {
        private val blocks = mutableMapOf<KClass<*>, (Context, BlockNode) -> View>()
        private val inlines = mutableMapOf<KClass<*>, (Context, RichNode, TextView) -> View>()

        fun <T : BlockNode> block(type: NodeType<T>, renderer: (Context, T) -> View) = apply {
            blocks[type.valueClass] = { context, node -> @Suppress("UNCHECKED_CAST") renderer(context, node as T) }
        }

        fun <T : RichNode> inline(type: NodeType<T>, renderer: (Context, T, TextView) -> View) = apply {
            inlines[type.valueClass] = { context, node, owner -> @Suppress("UNCHECKED_CAST") renderer(context, node as T, owner) }
        }

        fun build() = TmarkWidgets(blocks.toMap(), inlines.toMap())
    }

    companion object {
        val Empty = Builder().build()
        fun build(block: Builder.() -> Unit): TmarkWidgets = Builder().apply(block).build()
    }
}
