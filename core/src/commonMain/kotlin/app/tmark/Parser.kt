package app.tmark

/** Syntax/shape error; [offset] is a UTF-16 offset when known. */
class TmarkException(message: String, val offset: Int? = null) :
    IllegalArgumentException("tmark: $message" + (offset?.let { " at offset $it" } ?: ""))

internal fun fail(message: String): Nothing = throw TmarkException(message)
internal sealed interface Part
internal data class TextPart(val text: String) : Part
internal data class NodePart(val tag: String, val body: List<Part>, val raw: String) : Part
internal data class FieldPart(val name: String, val value: List<Part>) : Part
internal fun clean(parts: List<Part>): List<Part> = parts.filterNot {
    it is TextPart && '\n' in it.text && it.text.isBlank()
}

internal class Parser(input: String) {
    private val source = input.replace("\r\n", "\n").also {
        if ('\r' in it || it.startsWith('\uFEFF')) fail("invalid line ending or BOM")
    }
    private var pos = 0
    private var depth = 0
    private var fieldDepth = 0
    fun parse(): List<Part> = parts(false)
    private fun error(message: String): Nothing = throw TmarkException(message, pos)
    private fun parts(nested: Boolean): List<Part> {
        val result = mutableListOf<Part>()
        var unnamed = false
        val names = mutableSetOf<String>()
        while (pos < source.length) {
            val part = when (source[pos]) {
                '}' -> { if (!nested) error("unexpected }"); pos++; return result }
                '{' -> node()
                '#' -> field()
                else -> text()
            }
            if (part is FieldPart) {
                if (unnamed) error("named field after content")
                if (!names.add(part.name)) error("duplicate field '${part.name}'")
            } else if (part !is TextPart || !part.text.isBlank() || '\n' !in part.text) unnamed = true
            result += part
        }
        if (nested) error("missing }")
        return result
    }
    private fun node(): NodePart {
        val start = pos++
        if (++depth > TMARK_MAX_DEPTH) error("max depth $TMARK_MAX_DEPTH exceeded")
        try {
            if (pos >= source.length) error("missing tag terminator")
            if (source[pos] == '}') { pos++; return NodePart("", emptyList(), source.substring(start, pos)) }
            val tagStart = pos
            while (pos < source.length && source[pos] !in ";{}#\\") {
                pos++
            }
            if (pos < source.length && source[pos] == ';') {
                val tag = source.substring(tagStart, pos++)
                if (!validName(tag)) error("invalid tag")
                return NodePart(tag, parts(true), source.substring(start, pos))
            }
            pos = tagStart
            return NodePart("", parts(true), source.substring(start, pos))
        } finally { depth-- }
    }
    private fun field(): FieldPart {
        pos++
        val start = pos
        while (pos < source.length && (source[pos] in 'a'..'z' || source[pos] in '0'..'9' || source[pos] in "_-")) pos++
        if (pos == start || pos >= source.length || source[pos] != '{' || !validName(source.substring(start, pos))) error("malformed field")
        val name = source.substring(start, pos++)
        // Go limits nodes; also bound field-only recursion to prevent stack overflow.
        if (++fieldDepth > TMARK_MAX_DEPTH) error("max field depth exceeded")
        try { return FieldPart(name, parts(true)) } finally { fieldDepth-- }
    }
    private fun text(): TextPart = TextPart(buildString {
        while (pos < source.length && source[pos] !in "{}#") {
            if (source[pos] == '\\') { pos++; if (pos == source.length || source[pos] !in "#{}\\") error("invalid escape") }
            append(source[pos++])
        }
    })
    private fun validName(value: String): Boolean = value.length in 1..32 && value.all { it in 'a'..'z' || it in '0'..'9' || it in "_-" }
}
