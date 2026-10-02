package app.tmark

internal class Decoder(private val registry: TmarkRegistry, private val soft: Boolean) {
    fun registryType(type: kotlin.reflect.KClass<*>): NodeType<*>? = registry.find(type)
    fun single(parts: List<Part>): TmarkValue = when (val part = clean(parts).singleOrNull()) {
        is TextPart -> TText(part.text)
        is NodePart -> node(part)
        else -> fail("expected single value")
    }

    fun node(node: NodePart): TmarkValue {
        val type = registry.find(node.tag)
            ?: return if (soft && node.tag.isNotEmpty()) Unknown(node.raw) else fail("unknown tag '${node.tag}'")
        return type.readBody(node.body, this, type.layout != TmarkLayout.COMPACT).also(::validateNode)
    }
    fun concrete(node: NodePart, type: NodeType<*>): TmarkValue {
        val body = Parser(node.raw.substring(1, node.raw.length - 1)).parse()
        return type.readBody(body, this, type.layout != TmarkLayout.COMPACT).also(::validateNode)
    }
}

internal class Body(parts: List<Part>, expanded: Boolean) {
    private val unnamed = mutableListOf<Part>()
    private var contentUsed = false
    private val fields = linkedMapOf<String, List<Part>>()
    init {
        for (part in if (expanded) clean(parts) else parts) {
            if (part is FieldPart) {
                if (fields.put(part.name, part.value) != null) fail("duplicate field '${part.name}'")
            } else unnamed += part
        }
    }
    fun field(name: String): List<Part>? = fields.remove(name)
    fun content(): List<Part>? { contentUsed = true; return unnamed.takeIf { it.isNotEmpty() } }
    fun done() {
        if (fields.isNotEmpty()) fail("unknown field '${fields.keys.first()}'")
        if (!contentUsed && clean(unnamed).isNotEmpty()) fail("unexpected unnamed content")
    }
}

internal fun text(parts: List<Part>, expanded: Boolean = false): String {
    val value = parts.joinToString("") { (it as? TextPart)?.text ?: fail("expected text") }
    return if (expanded) value.removePrefix("\n").removeSuffix("\n") else value
}
