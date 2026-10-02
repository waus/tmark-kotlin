package app.tmark.android

import android.os.Parcelable
import android.text.Spanned
import android.text.style.ClickableSpan
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tmark.*
import app.tmark.TableRow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TmarkViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = android.view.ContextThemeWrapper(
        instrumentation.targetContext,
        com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar,
    )
    private fun ui(test: () -> Unit) = instrumentation.runOnMainSync(test)
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>()
    private fun clickText(view: TextView) {
        val text = view.text as Spanned
        text.getSpans(0, text.length, ClickableSpan::class.java).first().onClick(view)
    }

    @Test fun mediaFormatsAreDetectedFromBytes() {
        val assets = instrumentation.context.assets
        fun sample(name: String) = assets.open("media/$name").use { it.readBytes() }
        for ((name, format) in mapOf(
            "image.jpg" to ImageFormat.JPEG, "image.png" to ImageFormat.PNG,
            "image.webp" to ImageFormat.WEBP, "image.avif" to ImageFormat.AVIF,
            "animation.json" to ImageFormat.LOTTIE,
        )) assertEquals(name, format, imageFormat(sample(name)))
        for (name in listOf("image.gif", "image.jxl")) assertNull(name, imageFormat(sample(name)))
        for ((name, format) in mapOf(
            "tone.mp3" to AudioFormat.MP3, "tone.flac" to AudioFormat.FLAC,
            "tone-opus.ogg" to AudioFormat.OGG_OPUS, "tone-aac-lc.m4a" to AudioFormat.MP4_AAC_LC,
        )) assertEquals(name, format, audioFormat(sample(name)))
        for (name in listOf("tone-aac-he.m4a", "tone-vorbis.ogg", "tone-aac-lc.aac",
            "tone-opus.webm", "tone-flac.ogg", "tone.wav")) assertNull(name, audioFormat(sample(name)))
    }

    @Test fun allRemoteExamplesInflateAndMeasure() {
        val examples = listOf(
            "all_widgets.tmark", "edge_cases.tmark", "edge_empties.tmark",
            "inline_formatting.tmark", "lists_and_tasks.tmark", "maps_and_events.tmark",
            "math_and_code.tmark", "media_formats.tmark", "media_gallery.tmark",
            "quotes_and_details.tmark", "references_and_anchors.tmark", "tables.tmark",
        )
        for (file in examples) {
            val connection = java.net.URL("https://tmark.waus.app/examples/$file").openConnection() as java.net.HttpURLConnection
            val source = try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                assertEquals("Failed to load $file", 200, connection.responseCode)
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally { connection.disconnect() }
            ui {
                val view = TmarkView(context)
                view.render(source, soft = true)
                view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, 1080, view.measuredHeight)
                assertTrue("Empty example: $file", view.childCount > 0)
            }
        }
    }
    @Test fun textSpoilerMasksContentUntilClicked() = ui {
        val view = TmarkView(context)
        view.render(Document(content = listOf(Paragraph(listOf(TText("before "), Spoiler(listOf(TText("secret"))))))))
        val text = texts(view).first()
        val spans = text.text as Spanned
        val start = spans.indexOf("secret")
        val foreground = spans.getSpans(start, start + 6, android.text.style.ForegroundColorSpan::class.java).single()
        val background = spans.getSpans(start, start + 6, android.text.style.BackgroundColorSpan::class.java).single()
        assertEquals(foreground.foregroundColor, background.backgroundColor)
        clickText(text)
        val revealed = texts(view).first().text as Spanned
        assertEquals(0, revealed.getSpans(0, revealed.length, android.text.style.BackgroundColorSpan::class.java).size)
    }
    @Test fun imageSpoilerRevealsWithoutLoadingAgain() = ui {
        var calls = 0
        val view = TmarkView(context)
        view.render(Document(content = listOf(ImageNode(hasSpoiler = true, src = "hash"))), MediaContentLoader { calls++; byteArrayOf() })
        assertEquals(0, calls)
        val image = descendants(view).filterIsInstance<NativeImageView>().single()
        assertTrue(image.isSpoilerHidden)
        image.performClick()
        assertFalse(image.isSpoilerHidden)
        assertEquals(0, calls)
    }
    @Test fun imageSpoilerPreviewIsBlurred() {
        val source = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels = IntArray(128 * 128) { if (it % 128 < 64) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        source.setPixels(pixels, 0, 128, 0, 0, 128, 128)
        val preview = blurSpoiler(source)
        val gray = android.graphics.Color.red(preview.getPixel(64, 64))
        assertTrue(gray in 32..223)
    }
    @Test fun detailsAndSpoilersRestoreState() = ui {
        val document = Document(content = listOf(
            Details(summary = listOf(TText("More")), children = listOf(Paragraph(listOf(TText("inside"))))),
            Details(summary = listOf(TText("Next"))),
        ))
        val view = TmarkView(context).apply { id = 123; render(document) }
        assertEquals(1, view.childCount)
        val header = texts(view).first { it.text.toString() == "More" }.parent as View
        val inside = texts(view).first { it.text.toString() == "inside" }
        assertEquals(View.GONE, (inside.parent as View).visibility)
        header.performClick()
        assertEquals(View.VISIBLE, (inside.parent as View).visibility)
        val saved = SparseArray<Parcelable>()
        view.saveHierarchyState(saved)
        val restored = TmarkView(context).apply { id = 123; render(document); restoreHierarchyState(saved) }
        val restoredInside = texts(restored).first { it.text.toString() == "inside" }
        assertEquals(View.VISIBLE, (restoredInside.parent as View).visibility)
    }
    @Test fun linksTasksAndRendererOverrides() = ui {
        var href = ""
        var checked = false
        val view = TmarkView(context).apply {
            config = TmarkConfig(onLink = { href = it.toString() }, onTaskChange = { _, value -> checked = value },
                blockRenderer = { ctx, node -> if (node is MathBlock) TextView(ctx).apply { text = "custom math" } else null })
        }
        view.render(Document(content = listOf(Paragraph(listOf(Link("https://example.com", listOf(TText("link"))))), ListBlock(listOf(ListItem(checked = false))), MathBlock("x"))))
        clickText(texts(view).first())
        assertEquals("https://example.com", href)
        descendants(view).filterIsInstance<CheckBox>().first().performClick()
        assertTrue(checked)
        assertTrue(texts(view).any { it.text.toString() == "custom math" })
    }
    @Test fun typedWidgetsRenderBothMathKinds() = ui {
        val widgets = TmarkWidgets.build {
            inline(Math) { ctx, node, _ -> TextView(ctx).apply { text = "inline:${node.expression}" } }
            block(MathBlock) { ctx, node -> TextView(ctx).apply { text = "block:${node.expression}" } }
        }
        val view = TmarkView(context).apply { config = TmarkConfig(widgets = widgets) }
        view.render(Document(content = listOf(Paragraph(listOf(TText("before "), Math("x"))), MathBlock("y"))))
        assertTrue(texts(view).any { it.text.toString() == "block:y" })
        val inlineText = texts(view).first { it.text.toString() == "before x" }.text as Spanned
        assertEquals(1, inlineText.getSpans(0, inlineText.length, android.text.style.ReplacementSpan::class.java).size)
    }
    @Test fun unresolvedIconShowsBracketedAltText() = ui {
        val view = TmarkView(context)
        view.render(Document(content = listOf(Paragraph(listOf(TText("icon: "), Icon("missing", "C language logo"))))))
        assertEquals("icon: [C language logo]", texts(view).single().text.toString())
    }
    @Test fun slideshowUsesComposePagerAndNativeTableHasSpans() = ui {
        val view = TmarkView(context)
        view.render(Document(content = listOf(Slideshow(children = listOf(ImageNode(src = "a"), ImageNode(src = "b"))), Table(rows = listOf(TableRow(listOf(Cell(rowspan = 2), Cell())), TableRow(listOf(Cell())))))))
        assertEquals(1, descendants(view).filterIsInstance<androidx.compose.ui.platform.ComposeView>().size)
        val table = descendants(view).filterIsInstance<GridLayout>().first()
        assertEquals(2, table.rowCount)
        assertEquals(2, table.columnCount)
        assertEquals(3, table.childCount)
    }
    @Test fun slideshowHeightUsesTallestImageAndLimit() = ui {
        val frame = SlideshowSizeFrame(context, listOf("a", "b"), MediaContentLoader { byteArrayOf() })
        frame.setImageSize(0, 400, 200)
        frame.setImageSize(1, 200, 400)
        frame.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        assertEquals(400, frame.measuredHeight)
        frame.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(5000, View.MeasureSpec.AT_MOST))
        assertEquals((420 * context.resources.displayMetrics.density).roundToInt(), frame.measuredHeight)
    }
    @Test fun groupedMediaUsesReferenceTilePatterns() {
        val topAndTwo = MediaGeometry(listOf(1.6, 1.0, 1.0), 360.0, 96.0, 2.0).layout()
        assertEquals(360, topAndTwo[0].width)
        assertEquals(topAndTwo[1].top, topAndTwo[2].top)
        assertTrue(topAndTwo[1].top > 0)
        val leftAndTwo = MediaGeometry(listOf(0.5, 1.0, 1.0), 360.0, 96.0, 2.0).layout()
        assertEquals(0, leftAndTwo[0].left)
        assertEquals(leftAndTwo[1].left, leftAndTwo[2].left)
        assertTrue(leftAndTwo[2].top > leftAndTwo[1].top)
        assertEquals(6, MediaGeometry(List(6) { 1.0 }, 360.0, 96.0, 2.0).layout().size)
    }
    @Test fun preformattedHasSurfaceAndListMarkersAlignWithText() = ui {
        val view = TmarkView(context)
        view.render(Document(content = listOf(
            Preformatted("code"),
            ListBlock(listOf(
                ListItem(order = 10, children = listOf(Paragraph(listOf(TText("number"))))),
                ListItem(checked = false, children = listOf(Paragraph(listOf(TText("task"))))),
            )),
        )))
        assertTrue(view.getChildAt(0).background is android.graphics.drawable.GradientDrawable)
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 1080, view.measuredHeight)
        val list = view.getChildAt(1) as ViewGroup
        val first = list.getChildAt(0) as ViewGroup
        val second = list.getChildAt(1) as ViewGroup
        assertEquals(first.getChildAt(1).left, second.getChildAt(1).left)
        val checkbox = second.getChildAt(0)
        val textColumn = second.getChildAt(1) as ViewGroup
        val label = textColumn.getChildAt(0)
        val checkboxCenter = (checkbox.top + checkbox.bottom) / 2
        val labelCenter = textColumn.top + (label.top + label.bottom) / 2
        assertTrue("Task checkbox and label are vertically misaligned: $checkboxCenter vs $labelCenter",
            kotlin.math.abs(checkboxCenter - labelCenter) <= (2 * context.resources.displayMetrics.density).roundToInt())
    }
    @Test fun unknownMarkupAndCodeHaveNoBackgroundAndCompactCopy() = ui {
        val raw = "{future;#mode{x}{p;raw content}}"
        val view = TmarkView(context)
        view.render(Document(content = listOf(Unknown(raw), Preformatted("val x = 1"), Paragraph(listOf(Code("inline"), Unknown(raw))))))
        assertTrue(texts(view).any { it.text.toString() == raw })
        assertTrue(texts(view).any { it.text.toString() == "inline$raw" })
        texts(view).forEach { text ->
            val spans = text.text as? Spanned
            assertEquals(0, spans?.getSpans(0, spans.length, android.text.style.BackgroundColorSpan::class.java)?.size ?: 0)
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 1080, view.measuredHeight)
        val copies = descendants(view).filterIsInstance<com.google.android.material.button.MaterialButton>()
        assertEquals(2, copies.size)
        copies.forEach {
            assertEquals(context.getString(R.string.tmark_copy), it.contentDescription)
            assertTrue(it.width < view.width / 3)
            assertEquals((it.parent as View).width, it.right)
        }
    }
    @Test fun textUsesMaterial3ThemeColor() = ui {
        val view = TmarkView(context)
        view.render(Document(content = listOf(Paragraph(listOf(TText("color"))))))
        assertEquals(com.google.android.material.color.MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface), texts(view).first().currentTextColor)
    }
    @Test fun captionsAndDescriptionsAreCentered() = ui {
        fun label(value: String) = listOf(TText(value))
        fun caption(value: String) = Caption(label(value), label("$value credit"))
        val view = TmarkView(context).apply { config = TmarkConfig(showDocumentHeader = true) }
        view.render(Document(description = "description", content = listOf(
            ImageNode(caption = caption("image")),
            VideoNode(caption = caption("video")),
            AudioNode(caption = caption("audio")),
            Collage(caption = caption("collage")),
            Slideshow(caption = caption("slideshow")),
            MapBlock(caption = caption("map")),
            Table(caption = label("table")),
            Blockquote(credit = label("quote credit")),
            PullQuote(credit = label("pull quote credit")),
        )), MediaContentLoader { byteArrayOf() })
        val captions = listOf("description", "image", "image credit", "video", "video credit",
            "audio", "audio credit", "collage", "collage credit", "slideshow", "slideshow credit",
            "map", "map credit", "table", "quote credit", "pull quote credit")
        captions.forEach { label ->
            val text = texts(view).single { it.text.toString() == label }
            assertEquals(label, Gravity.CENTER_HORIZONTAL, text.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        }
    }
    @Test fun applicationSchemasParseRenderAndFallBackToWireText() = ui {
        val codec = TmarkCodec(TmarkRegistry.Default.registering(AppBadge, AppPanel))
        val view = TmarkView(context).apply {
            config = TmarkConfig(codec = codec,
                blockRenderer = { ctx, node -> if (node is AppPanel) TextView(ctx).apply { text = "Panel: ${node.text}" } else null },
                inlineRenderer = { _, node -> if (node is AppBadge) "@${node.label}" else null })
        }
        val source = "{document;{app-panel;Hello}{p;{app-badge;#label{Alice}}}}"
        view.render(source)
        assertTrue(texts(view).any { it.text.toString() == "Panel: Hello" })
        assertTrue(texts(view).any { it.text.toString() == "@Alice" })
        view.config = TmarkConfig(codec = codec)
        assertTrue(texts(view).any { it.text.toString() == "{app-panel;Hello}" })
        assertTrue(texts(view).any { it.text.toString() == "{app-badge;#label{Alice}}" })
    }

    @Test fun imageDecodingStartsWithNonzeroSampleAndScalesLargeImages() {
        val file = java.io.File.createTempFile("tmark-image", ".png", context.cacheDir)
        try {
            val source = android.graphics.Bitmap.createBitmap(512, 256, android.graphics.Bitmap.Config.ARGB_8888)
            source.eraseColor(android.graphics.Color.RED)
            file.outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
            val decoded = decodeBitmap(file.readBytes(), 128)
            assertEquals(128, decoded.width)
            assertEquals(64, decoded.height)
            assertEquals(android.graphics.Color.RED, decoded.getPixel(0, 0))
            decoded.recycle()
        } finally { file.delete() }
    }

}


// This is a separate consumer module of core: schemas only use its public API.
private data class AppBadge(val label: String) : RichNode {
    companion object : NodeType<AppBadge>(AppBadge::class, "app-badge") {
        val label = field("label", FieldTypes.string, AppBadge::label)
        override fun create(values: FieldValues) = AppBadge(values[label])
    }
}
private data class AppPanel(val text: String) : BlockNode {
    companion object : NodeType<AppPanel>(AppPanel::class, "app-panel") {
        val text = content(FieldTypes.string, AppPanel::text) { "" }
        override fun create(values: FieldValues) = AppPanel(values[text])
    }
}
