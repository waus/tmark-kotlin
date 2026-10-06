package app.tmark

import kotlin.test.*

class TmarkTest {
    @Test fun typedDocument() {
        val doc = Tmark.decodeDocument("{document;#url{https://example.com}#title{Заголовок 🌍}{p;Hello {b;world}!}{img;#has_spoiler{t}#caption{#credit{Author}Caption}hash}}")
        assertEquals("Заголовок 🌍", doc.title)
        assertEquals(listOf(TText("Hello "), Bold(listOf(TText("world"))), TText("!")), (doc.content[0] as Paragraph).children)
        val image = doc.content[1] as ImageNode
        assertTrue(image.hasSpoiler)
        assertEquals(listOf(TText("Author")), image.caption!!.credit)
        assertEquals(doc, Tmark.decodeDocument(Tmark.encode(doc)))
    }
    @Test fun escapedUnicodeAndWhitespace() {
        for (text in listOf("Hello 👨‍👩‍👧‍👦", "\\#{};", " one \n two ", "\t", "e\u0301", "")) {
            val code = Code(text)
            assertEquals(code, Tmark.decode(Tmark.encode(code)))
        }
        assertEquals(Preformatted(text = "\nhello\n"), Tmark.decode("{pre;\n\nhello\n\n}"))
        assertEquals(listOf(TText(" ")), (Tmark.decode("{p; }") as Paragraph).children)
    }
    @Test fun allTypesRoundTrip() {
        val rich = listOf<RichNode>(
            TText("Text"), Link("https://example.com", listOf(TText("link"))), AnchorLink("a"), Reference("r"), ReferenceLink("r"),
            Bold(), Italic(), Marked(), Underline(), Strikethrough(), Spoiler(), Subscript(), Superscript(),
            DateTimeNode(1234567890, "UTC"), Code("#{}\\"), Math("x^2"), Icon("icon.png", "alt")
        )
        val cap = Caption(rich, listOf(TText("caption")))
        val blocks = listOf<BlockNode>(
            Paragraph(rich), Header(2, rich), Preformatted("val x = 1", "kotlin"), MathBlock("x^2"), Anchor("a"), Divider,
            Blockquote(listOf(Paragraph(rich)), rich), PullQuote(rich, rich),
            ListBlock(listOf(ListItem(listOf(Paragraph(rich)), "A", 3, false))),
            MapBlock(55.75, 37.61, 12, cap), ImageNode("a.png", cap, true), VideoNode("preview.png", "v.mp4", cap, true, true), AudioNode("a.mp3", cap),
            Collage(listOf(ImageNode(src = "a.png")), cap), Slideshow(listOf(VideoNode(preview = "preview.png", src = "v.mp4")), cap),
            Table(listOf(TableRow(listOf(Cell(rich, true, 2, 3, TableCellAlign.right, TableCellValign.bottom)))), rich, true, true),
            Details(listOf(Paragraph(rich), Divider), rich, true)
        )
        for (node in rich + blocks) assertEquals(node, Tmark.decode(Tmark.encode(node)), node.toString())
        val doc = Document("url", "title", "description", "name", "author", "image", listOf(AttachedMedia("hash", listOf(Divider))), listOf(Divider))
        assertEquals(doc, Tmark.decode(Tmark.encode(doc)))
    }
    @Test fun strictAndSoftUnknown() {
        val source = "{future;#mode{x}{p;hello}}"
        assertFailsWith<TmarkException> { Tmark.decode(source) }
        assertEquals(source, Tmark.encode(Tmark.decode(source, soft = true)))
        assertFailsWith<TmarkException> { Tmark.decode("{p;#bad{x}hello}", soft = true) }
        assertFailsWith<TmarkException> { Tmark.encode(Unknown("{p;a}{p;b}")) }
    }
    @Test fun malformedInput() {
        for (source in listOf("", "{p;", "}", "{p}", "\\", "{p;#a}", "{h;#s{1}#s{2}x}", "{img;#has_spoiler{yes}x}", "{h;#s{no}x}", "{li;#order{no}}", "{table;{td;x}}", "{p;{hr;}}", "{list;{p;x}}", "{hr;x}", "{map;text}", "{td;#align{diagonal}}", "{h;#s{}}", "{map;#lat{}}", "{li;#order{}}")) {
            assertFailsWith<TmarkException>(source) { Tmark.decode(source) }
        }
    }
    @Test fun depthLimitsIncludeUnknownFields() {
        assertFailsWith<TmarkException> { Tmark.decode("{b;".repeat(17) + "x" + "}".repeat(17)) }
        assertFailsWith<TmarkException> { Tmark.decode("{future;" + "#x{".repeat(1000) + "}".repeat(1001), soft = true) }
    }
    @Test fun omittedFieldsFollowGoZeroValues() {
        assertEquals(Document(), Tmark.decode("{document;\n}"))
        assertFailsWith<TmarkException> { Tmark.decode("{h;}") }
        assertEquals(ImageNode(), Tmark.decode("{img;}"))
        assertFailsWith<TmarkException> { Tmark.decode("{video;clip.mp4}") }
        assertEquals(VideoNode(preview = "cover.png", src = "clip.mp4"), Tmark.decode("{video;#preview{cover.png}clip.mp4}"))
        assertEquals(VideoNode(preview = "cover.png", src = "clip.mp4", loop = true), Tmark.decode("{video;#preview{cover.png}#loop{t}clip.mp4}"))
        assertEquals("{video;#preview{cover.png}#loop{t}clip.mp4}", Tmark.encode(VideoNode(preview = "cover.png", src = "clip.mp4", loop = true)))
        assertEquals(ListItem(checked = false), Tmark.decode("{li;#checked{f}}"))
    }
    @Test fun tableSpansAvoidCollisions() {
        val cells = placeCells(listOf(TableRow(listOf(Cell(rowspan = 2), Cell(colspan = 2))), TableRow(listOf(Cell(), Cell()))))
        assertEquals(listOf(0, 1, 1, 2), cells.map { it.column })
        assertEquals(listOf(0, 0, 1, 1), cells.map { it.row })
        assertEquals(2, cells[0].rowSpan)
        assertEquals(2, cells[1].columnSpan)
    }
    @Test fun optionalEmptyStringsAndSmallCoordinates() {
        for (node in listOf(Icon("url", ""), Preformatted("text", ""), Document(description = ""))) {
            assertEquals(node, Tmark.decode(Tmark.encode(node)))
        }
        assertFailsWith<TmarkException> { Tmark.encode(ListItem(type = "")) }
        assertEquals("{map;#lat{0.0000001}#lon{100000000}}", Tmark.encode(MapBlock(1e-7, 1e8)))
        assertFailsWith<TmarkException> { Tmark.decode("{future;#x{a}#x{b}}", soft = true) }
    }
    @Test fun orderedLabels() {
        assertEquals("aa", listLabel(27, "a")); assertEquals("AZ", listLabel(52, "A"))
        assertEquals("xiv", listLabel(14, "i")); assertEquals("MCMXCIV", listLabel(1994, "I"))
        assertEquals("0", listLabel(0, "I"))
    }
}
