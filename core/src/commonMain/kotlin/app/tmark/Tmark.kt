package app.tmark

/** A codec with an immutable registry. Each operation has its own parser/writer state. */
class TmarkCodec(val registry: TmarkRegistry = TmarkRegistry.Default) {
    fun decode(source: String, soft: Boolean = false): TmarkValue = Decoder(registry, soft).single(Parser(source).parse())
    fun decodeDocument(source: String, soft: Boolean = false): Document =
        decode(source, soft) as? Document ?: fail("expected document")
    fun encode(value: TmarkValue): String = Writer(registry).value(value).also {
        // Include verbatim Unknown nodes in the aggregate wire nesting check.
        Parser(it).parse()
    }
}

/** Convenient entry points using the standard types. Use [TmarkCodec] for application schemas. */
object Tmark {
    private val codec = TmarkCodec()
    fun decode(source: String, soft: Boolean = false): TmarkValue = codec.decode(source, soft)
    fun decodeDocument(source: String, soft: Boolean = false): Document = codec.decodeDocument(source, soft)
    fun encode(value: TmarkValue): String = codec.encode(value)
}
