package consumer

import app.tmark.*
import kotlin.test.*

// These models use only the public extension API, outside the library's package.
private data class Mention(val user: String, val children: RichText = emptyList()) : RichNode {
    companion object : NodeType<Mention>(Mention::class, "mention") {
        val user = field("user", FieldTypes.string, Mention::user)
        val children = content(FieldTypes.richText, Mention::children) { emptyList() }
        override fun create(values: FieldValues) = Mention(values[user], values[children])
    }
}

private data class Color(val hex: String)
private val colorType = FieldTypes.string.mapped("Color", { Color(it) }, { it.hex })
private data class Appearance(val color: Color, val label: String?) {
    companion object : RecordType<Appearance>(Appearance::class) {
        val color = field("color", colorType, Appearance::color)
        val label = optional("label", FieldTypes.string, Appearance::label)
        override fun create(values: FieldValues) = Appearance(values[color], values[label])
    }
}

private data class Callout(val appearance: Appearance?, val priority: Int = 0, val children: RichBlocks = emptyList()) : BlockNode {
    companion object : NodeType<Callout>(Callout::class, "callout", TmarkLayout.EXPANDED) {
        val appearance = optional("appearance", FieldTypes.record(Appearance), Callout::appearance)
        val priority = field("priority", FieldTypes.int, Callout::priority) { 0 }
        val children = content(FieldTypes.blocks, Callout::children) { emptyList() }
        override fun create(values: FieldValues) = Callout(values[appearance], values[priority], values[children])
    }
}

private data class ChoiceRow(val items: List<Mention>) : BlockNode {
    companion object : NodeType<ChoiceRow>(ChoiceRow::class, "choice-row") {
        val items = content(FieldTypes.nodes<Mention>(), ChoiceRow::items) { emptyList() }
        override fun create(values: FieldValues) = ChoiceRow(values[items])
    }
}

private data class Choice(val selected: Mention, val alternatives: List<ChoiceRow>) : BlockNode {
    companion object : NodeType<Choice>(Choice::class, "choice", TmarkLayout.EXPANDED) {
        val selected = field("selected", FieldTypes.node<Mention>(), Choice::selected)
        val alternatives = content(FieldTypes.nodes<ChoiceRow>(), Choice::alternatives) { emptyList() }
        override fun create(values: FieldValues) = Choice(values[selected], values[alternatives])
    }
}

class RegistryTest {
    private val registry = TmarkRegistry.Default.registering(Mention, Callout, ChoiceRow, Choice)
    private val codec = TmarkCodec(registry)

    @Test fun extensionTypesWorkInsideStandardDocumentsAndFormatting() {
        val document = Document(content = listOf(Callout(Appearance(Color("#ff00ff"), ""), 3,
            listOf(Paragraph(listOf(Bold(listOf(Mention("42", listOf(TText("Alice")))))))))))
        val source = codec.encode(document)
        assertEquals(document, codec.decodeDocument(source))
        assertTrue("#appearance{#color{\\#ff00ff}#label{}}" in source)
        assertTrue(codec.decode("{callout;\n{p;empty optional record}\n}") is Callout)
        assertEquals(listOf("appearance", "priority", null), Callout.fields.map { it.name })
        assertEquals("Int", Callout.priority.type.description)
    }

    @Test fun explicitRegistrationDoesNotChangeDefaultOrOtherRegistries() {
        val wire = "{mention;#user{alice}Hi}"
        assertEquals(Mention("alice", listOf(TText("Hi"))), codec.decode(wire))
        assertFailsWith<TmarkException> { Tmark.decode(wire) }
        assertEquals(Unknown(wire), Tmark.decode(wire, soft = true))
        assertFailsWith<TmarkException> { TmarkCodec(TmarkRegistry.Empty.registering(Callout)).decode(wire) }
        assertEquals(wire, codec.encode(codec.decode(wire)))
    }

    @Test fun requiredFieldsAndNestedTypesAreValidated() {
        for (wire in listOf(
            "{mention;hello}", "{mention;#user{a}#user{b}}", "{mention;#user{a}#typo{x}}",
            "{mention;#user{a}{p;wrong category}}", "{callout;#priority{not an int}}",
            "{callout;#appearance{#color{red}#bad{x}}}", "{callout;{mention;#user{a}}}",
            "{choice;#selected{{p;wrong type}}}", "{choice;#selected{{mention;#user{x}}}{{p;wrong row type}}}",
        )) assertFailsWith<TmarkException>(wire) { codec.decode(wire, soft = true) }
        // A required named string may be explicitly empty, but cannot be omitted.
        assertEquals(Mention(""), codec.decode(codec.encode(Mention(""))))
    }

    @Test fun recordsAndTypedNodeFieldsRoundTrip() {
        val node = Choice(Mention("first"), listOf(ChoiceRow(listOf(Mention("second"))), ChoiceRow(emptyList())))
        assertEquals(node, codec.decode(codec.encode(node)))
        val source = "{callout;\n#priority{0}\n{future;#something{preserved}}\n}"
        assertEquals(source, codec.encode(codec.decode(source, soft = true)))
    }

    @Test fun registryRejectsDuplicateTagsAndClasses() {
        assertFailsWith<IllegalArgumentException> { registry.registering(Mention) }
        val sameTag = object : NodeType<Choice>(Choice::class, "mention") {
            override fun create(values: FieldValues) = Choice(Mention(""), emptyList())
        }
        assertFailsWith<IllegalArgumentException> { registry.registering(sameTag) }
        val sameClass = object : NodeType<Mention>(Mention::class, "other") {
            override fun create(values: FieldValues) = Mention("")
        }
        assertFailsWith<IllegalArgumentException> { registry.registering(sameClass) }
    }

    @Test fun malformedOrMutableSchemasCannotEnterTheRegistry() {
        assertFailsWith<IllegalArgumentException> {
            object : NodeType<Mention>(Mention::class, "bad;tag") {
                override fun create(values: FieldValues) = Mention("")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : NodeType<Mention>(Mention::class, "duplicate-fields") {
                val one = field("x", FieldTypes.string, Mention::user)
                val two = field("x", FieldTypes.string, Mention::user)
                override fun create(values: FieldValues) = Mention(values[one])
            }
        }
        val schema = object : NodeType<Mention>(Mention::class, "frozen") {
            fun mutate() { field("x", FieldTypes.string, Mention::user) }
            override fun create(values: FieldValues) = Mention("")
        }
        TmarkRegistry.Empty.registering(schema)
        assertFailsWith<IllegalStateException> { schema.mutate() }
    }

    @Test fun nonemptyDefaultsDoNotEraseEmptyValues() {
        val schema = object : NodeType<Mention>(Mention::class, "defaulted") {
            val user = field("user", FieldTypes.string, Mention::user) { "fallback" }
            val children = content(FieldTypes.richText, Mention::children)
            override fun create(values: FieldValues) = Mention(values[user], values[children])
        }
        val custom = TmarkCodec(TmarkRegistry.Empty.registering(schema))
        assertEquals(Mention("fallback"), custom.decode("{defaulted;}"))
        assertEquals("{defaulted;#user{}}", custom.encode(Mention("")))
        assertEquals(Mention(""), custom.decode(custom.encode(Mention(""))))
    }

    @Test fun namedFieldsAreWrittenBeforeContentRegardlessOfDeclarationOrder() {
        val schema = object : NodeType<Mention>(Mention::class, "body-first") {
            val children = content(FieldTypes.richText, Mention::children) { emptyList() }
            val user = field("user", FieldTypes.string, Mention::user)
            override fun create(values: FieldValues) = Mention(values[user], values[children])
        }
        val custom = TmarkCodec(TmarkRegistry.Empty.registering(schema))
        val mention = Mention("alice", listOf(TText("Hello")))
        assertEquals("{body-first;#user{alice}Hello}", custom.encode(mention))
        assertEquals(mention, custom.decode(custom.encode(mention)))
    }

    @Test fun customNodesHaveTheSameDepthLimit() {
        var node: BlockNode = Paragraph(listOf(TText("leaf")))
        repeat(TMARK_MAX_DEPTH) { node = Callout(null, children = listOf(node)) }
        assertFailsWith<TmarkException> { codec.encode(node) }
        assertFailsWith<TmarkException> { codec.decode("{callout;".repeat(17) + "}".repeat(17)) }
    }
}
