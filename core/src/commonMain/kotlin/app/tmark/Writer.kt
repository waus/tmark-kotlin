package app.tmark

internal class Writer(private val registry: TmarkRegistry) {
    fun registryType(type: kotlin.reflect.KClass<*>): NodeType<*>? = registry.find(type)
    private var nodeDepth = 0
    private var fieldDepth = 0

    fun value(value: TmarkValue, omitTag: Boolean = false): String = when (value) {
        is TText -> escape(value.text)
        is Unknown -> {
            if (Parser(value.raw).parse().singleOrNull() !is NodePart) fail("unknown must contain exactly one node")
            value.raw
        }
        else -> inNode {
            validateNode(value)
            val schema = registry.find(value) ?: fail("unregistered value type")
            schema.writeNode(value, this, omitTag)
        }
    }

    fun <T> inNode(block: () -> T): T {
        if (++nodeDepth > TMARK_MAX_DEPTH) fail("max depth $TMARK_MAX_DEPTH exceeded")
        try { return block() } finally { nodeDepth-- }
    }

    fun <T> inField(block: () -> T): T {
        if (++fieldDepth > TMARK_MAX_DEPTH) fail("max field depth exceeded")
        try { return block() } finally { fieldDepth-- }
    }
}

internal fun validateNode(value: TmarkValue) {
    when (value) {
        is Header -> if (value.size !in 1..6) fail("header size must be 1..6")
        is ListItem -> if (value.type != null && value.type !in setOf("a", "A", "i", "I", "1", "checkbox")) fail("invalid list item type")
        is Collage -> if (value.children.any { it !is ImageNode && it !is VideoNode }) fail("invalid collage child")
        is Slideshow -> if (value.children.any { it !is ImageNode && it !is VideoNode }) fail("invalid slideshow child")
    }
}

internal fun escape(s: String): String = buildString {
    for (c in s) { if (c in "#{}\\") append('\\'); append(c) }
}

/** Expand floating-point notation to the canonical decimal wire form. */
internal fun decimal(value: Double): String {
    if (value == 0.0) return "0"
    val raw = value.toString().lowercase()
    if ('e' !in raw) return raw.removeSuffix(".0")
    val mantissa = raw.substringBefore('e')
    val exponent = raw.substringAfter('e').toInt()
    val sign = if (mantissa.startsWith('-')) "-" else ""
    val unsigned = mantissa.removePrefix("-")
    val digits = unsigned.replace(".", "").trimEnd('0')
    val point = unsigned.indexOf('.').let { if (it < 0) unsigned.length else it } + exponent
    return sign + when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.take(point) + "." + digits.drop(point)
    }
}
