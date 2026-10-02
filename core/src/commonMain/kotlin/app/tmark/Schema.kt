package app.tmark

import kotlin.reflect.KClass

/** Wire layout is part of a type's schema, shared by decoding and encoding. */
enum class TmarkLayout { COMPACT, EXPANDED, WHEN_MULTIPLE }

/** A typed field token. A null [name] denotes the unnamed body. */
class TmarkField<O : Any, V> internal constructor(
    val name: String?,
    val type: FieldType<V>,
    internal val getter: (O) -> V,
    internal val default: (() -> V)?,
) {
    val required: Boolean get() = default == null

    internal fun read(parts: List<Part>?, decoder: Decoder, expanded: Boolean): V {
        if (parts == null) {
            if (name == null && default == null) return type.read(emptyList(), decoder, expanded)
            val fallback = default ?: fail("missing field '${name ?: "content"}'")
            return fallback()
        }
        return type.read(parts, decoder, expanded)
    }

    internal fun write(owner: O, writer: Writer, expanded: Boolean): List<String>? {
        val value = getter(owner)
        if (!required && type.omit(value) && value == default?.invoke()) return null
        return when {
            name != null -> writer.inField { listOf(type.write(value, writer)) }
            expanded -> type.writeExpanded(value, writer)
            else -> listOf(type.write(value, writer))
        }
    }

    internal fun size(owner: O): Int = type.size(getter(owner))
}

/** Values can only be read with tokens belonging to the schema being constructed. */
class FieldValues internal constructor(private val values: Map<TmarkField<*, *>, Any?>) {
    @Suppress("UNCHECKED_CAST")
    operator fun <V> get(field: TmarkField<*, V>): V {
        require(values.containsKey(field)) { "Field does not belong to this schema" }
        return values[field] as V
    }
}

/** Common field declaration API for tagged nodes and untagged records (e.g. captions). */
abstract class TmarkStructure<T : Any>(val valueClass: KClass<T>) {
    private val declared = mutableListOf<TmarkField<T, *>>()
    private var frozen = false
    val fields: List<TmarkField<T, *>> get() = declared.toList()

    protected fun <V> field(
        name: String,
        type: FieldType<V>,
        get: (T) -> V,
        default: (() -> V)? = null,
    ): TmarkField<T, V> {
        require(name.length in 1..32 && name.all { it in 'a'..'z' || it in '0'..'9' || it in "_-" }) {
            "Invalid field name '$name'"
        }
        return declare(name, type, get, default)
    }

    protected fun <V : Any> optional(name: String, type: FieldType<V>, get: (T) -> V?): TmarkField<T, V?> =
        field(name, type.optional(), get) { null }

    protected fun <V> content(type: FieldType<V>, get: (T) -> V, default: (() -> V)? = null): TmarkField<T, V> =
        declare(null, type, get, default)

    private fun <V> declare(name: String?, type: FieldType<V>, get: (T) -> V, default: (() -> V)?): TmarkField<T, V> {
        check(!frozen) { "Registered schemas cannot be changed" }
        require(declared.none { it.name == name }) { "Duplicate field '${name ?: "content"}'" }
        return TmarkField(name, type, get, default).also { declared += it }
    }

    protected abstract fun create(values: FieldValues): T

    internal fun freeze() { frozen = true }

    internal fun readBody(parts: List<Part>, decoder: Decoder, expanded: Boolean): T {
        val body = Body(parts, expanded)
        val values = mutableMapOf<TmarkField<*, *>, Any?>()
        for (field in declared) {
            val source = if (field.name == null) body.content() else body.field(field.name)
            values[field] = field.read(source, decoder, expanded && field.name == null)
        }
        body.done()
        return create(FieldValues(values))
    }

    internal fun writeBody(value: T, writer: Writer, layout: TmarkLayout): String {
        val expanded = layout == TmarkLayout.EXPANDED ||
            (layout == TmarkLayout.WHEN_MULTIPLE && (declared.firstOrNull { it.name == null }?.size(value) ?: 0) > 1)
        return buildString {
            for (field in declared) {
                if (field.name == null) continue
                val fragments = field.write(value, writer, false) ?: continue
                if (expanded) append('\n')
                append('#').append(field.name).append('{').append(fragments.single()).append('}')
            }
            declared.firstOrNull { it.name == null }?.write(value, writer, expanded)?.forEach { fragment ->
                if (expanded) append('\n')
                append(fragment)
            }
            if (expanded) append('\n')
        }
    }
}

/** Implement this in a model's companion object, then register that companion. */
abstract class NodeType<T : TmarkValue>(
    valueClass: KClass<T>,
    val tag: String,
    val layout: TmarkLayout = TmarkLayout.COMPACT,
) : TmarkStructure<T>(valueClass) {
    init {
        require(tag.length in 1..32 && tag.all { it in 'a'..'z' || it in '0'..'9' || it in "_-" }) { "Invalid node tag '$tag'" }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun writeNode(value: TmarkValue, writer: Writer, omitTag: Boolean = false): String {
        require(value::class == valueClass) { "Value does not match schema '$tag'" }
        return "{${if (omitTag) "" else "$tag;"}${writeBody(value as T, writer, layout)}}"
    }
}

/** Untagged structured field values use the same typed field machinery as nodes. */
abstract class RecordType<T : Any>(valueClass: KClass<T>) : TmarkStructure<T>(valueClass)

/** An immutable registry. Duplicate tags/classes fail rather than silently replacing a type. */
class TmarkRegistry private constructor(types: List<NodeType<*>>) {
    private val byTag = types.associateBy { it.tag }
    private val byClass = types.associateBy { it.valueClass }
    val types: List<NodeType<*>> get() = byTag.values.toList()

    init {
        require(byTag.size == types.size) { "Duplicate registered tag" }
        require(byClass.size == types.size) { "Duplicate registered value class" }
        require(types.none { it.valueClass == TText::class || it.valueClass == Unknown::class }) {
            "Text and raw unknown nodes are wire primitives"
        }
        types.forEach { it.freeze() }
    }

    fun registering(vararg types: NodeType<*>): TmarkRegistry = TmarkRegistry(this.types + types)
    internal fun find(tag: String): NodeType<*>? = byTag[tag]
    internal fun find(value: TmarkValue): NodeType<*>? = byClass[value::class]
    internal fun find(type: KClass<*>): NodeType<*>? = byClass[type]

    companion object {
        val Empty = TmarkRegistry(emptyList())
        val Default: TmarkRegistry by lazy { Empty.registering(*builtInTypes()) }
    }
}
