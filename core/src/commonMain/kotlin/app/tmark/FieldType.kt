package app.tmark

import kotlin.reflect.KClass

/** A composable field codec; mapping permits application-specific scalar/value types. */
class FieldType<T> internal constructor(
    val description: String,
    internal val read: (List<Part>, Decoder, Boolean) -> T,
    internal val write: (T, Writer) -> String,
    internal val omit: (T) -> Boolean = { false },
    internal val size: (T) -> Int = { 1 },
    internal val writeExpanded: (T, Writer) -> List<String> = { value, writer -> listOf(write(value, writer)) },
) {
    fun <V> mapped(description: String, decode: (T) -> V, encode: (V) -> T): FieldType<V> = FieldType(
        description,
        { parts, decoder, expanded -> decode(read(parts, decoder, expanded)) },
        { value, writer -> write(encode(value), writer) },
        { omit(encode(it)) },
        { size(encode(it)) },
        { value, writer -> writeExpanded(encode(value), writer) },
    )

    internal fun optional(): FieldType<T?> = FieldType(
        "$description?",
        { parts, decoder, expanded -> read(parts, decoder, expanded) },
        { value, writer -> if (value == null) "" else write(value, writer) },
        { it == null },
        { value -> if (value == null) 0 else size(value) },
        { value, writer -> if (value == null) emptyList() else writeExpanded(value, writer) },
    )
}

object FieldTypes {
    val string = FieldType("String", { parts, _, expanded -> text(parts, expanded) }, { value: String, _ -> escape(value) }, { it.isEmpty() })
    val int = scalar("Int", { if (!validNumber(it, true)) fail("invalid integer '$it'"); it.toIntOrNull() ?: fail("invalid integer '$it'") }, Int::toString)
    val long = scalar("Long", { if (!validNumber(it, true)) fail("invalid integer '$it'"); it.toLongOrNull() ?: fail("invalid integer '$it'") }, Long::toString)
    val double = scalar("Double", {
        if (!validNumber(it)) fail("invalid number '$it'")
        it.toDoubleOrNull()?.takeIf { n -> n.isFinite() } ?: fail("invalid number '$it'")
    }, { value: Double -> if (!value.isFinite()) fail("non-finite number"); decimal(value) })
    val boolean = FieldType("Boolean", { parts, _, _ ->
        when (text(parts)) { "t" -> true; "f" -> false; else -> fail("expected t or f") }
    }, { value: Boolean, _ -> if (value) "t" else "f" }, { !it })

    /** Scalar contents are always escaped by the codec, including custom scalars. */
    fun <T> scalar(description: String, parse: (String) -> T, format: (T) -> String): FieldType<T> = FieldType(
        description,
        { parts, _, expanded -> parse(text(parts, expanded)) },
        { value, _ -> escape(format(value)) },
    )

    fun <T : Enum<T>> enum(values: List<T>): FieldType<T> = scalar("Enum", { value ->
        values.firstOrNull { it.name == value } ?: fail("invalid enum '$value'")
    }, { it.name })

    fun <T : Any> record(schema: RecordType<T>): FieldType<T> {
        schema.freeze()
        return FieldType("Record", { parts, decoder, _ -> schema.readBody(parts, decoder, false) },
            { value, writer -> schema.writeBody(value, writer, TmarkLayout.COMPACT) })
    }
    fun <T : TmarkValue> record(schema: NodeType<T>): FieldType<T> = FieldType(
        "Record", { parts, decoder, _ -> schema.readBody(parts, decoder, false) },
        { value, writer -> schema.writeBody(value, writer, TmarkLayout.COMPACT) },
    )

    inline fun <reified T : TmarkValue> node(): FieldType<T> = node(T::class)
    fun <T : TmarkValue> node(type: KClass<T>): FieldType<T> = FieldType("Node", { parts, decoder, _ ->
        val schema = decoder.registryType(type)
        if (schema != null) checked(type, schema.readBody(parts, decoder, schema.layout != TmarkLayout.COMPACT))
        else {
            val parsed = clean(parts).singleOrNull() as? NodePart ?: fail("expected single node")
            checked(type, decoder.node(parsed))
        }
    }, { value, writer ->
        val concrete = writer.registryType(type) != null
        writer.value(value, concrete).let { if (concrete) it.substring(1, it.length - 1) else it }
    })

    inline fun <reified T : TmarkValue> nodes(): FieldType<List<T>> = nodes(T::class)
    fun <T : TmarkValue> nodes(type: KClass<T>): FieldType<List<T>> = sequence(type, allowText = false)

    val richText: FieldType<RichText> = sequence(RichNode::class, allowText = true)
    val blocks: FieldType<RichBlocks> = nodes(BlockNode::class)

    private fun <T : TmarkValue> sequence(type: KClass<T>, allowText: Boolean): FieldType<List<T>> = FieldType(
        if (allowText) "RichText" else "Nodes",
        { parts, decoder, _ -> (if (allowText) parts else clean(parts)).map {
            val value = when (it) {
                is TextPart -> if (allowText) TText(it.text) else fail("expected node")
                is NodePart -> decoder.registryType(type)?.let { schema -> decoder.concrete(it, schema) } ?: decoder.node(it)
                else -> fail("unexpected field in content")
            }
            checked(type, value)
        } },
        { value, writer -> value.joinToString("") { writer.value(it, !allowText && writer.registryType(type) != null) } },
        { it.isEmpty() }, { it.size }, { value, writer -> value.map { writer.value(it, !allowText && writer.registryType(type) != null) } },
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T : TmarkValue> checked(type: KClass<T>, value: TmarkValue): T {
        if (!type.isInstance(value)) fail("wrong node category")
        return value as T
    }
}

private fun validNumber(value: String, integer: Boolean = false): Boolean =
    value != "-0" && (if (integer) Regex("^-?(0|[1-9][0-9]*)$") else Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?$")).matches(value)
