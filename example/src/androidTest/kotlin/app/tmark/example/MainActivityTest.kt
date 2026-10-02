package app.tmark.example

import com.google.android.material.textfield.MaterialAutoCompleteTextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tmark.android.TmarkView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @Test fun inlineMathWidgetAttachesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = TmarkView(activity).apply {
                    config = app.tmark.android.TmarkConfig(widgets = RaTeXWidgets)
                    render(app.tmark.Document(content = listOf(app.tmark.Paragraph(listOf(
                        app.tmark.TText("Before "), app.tmark.Math("E=mc^2"), app.tmark.TText(" after"),
                    )))))
                }
                activity.setContentView(view)
                val text = (view.getChildAt(0) as android.widget.TextView).text as android.text.Spanned
                assertEquals(1, text.getSpans(0, text.length, android.text.style.ReplacementSpan::class.java).size)
            }
            android.os.SystemClock.sleep(2_000)
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
        }
    }
    private fun awaitDocument(scenario: ActivityScenario<MainActivity>, name: String = "all_widgets.tmark") {
        var loaded = false
        val deadline = android.os.SystemClock.uptimeMillis() + 35_000
        while (!loaded && android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { loaded = it.findViewById<TmarkView>(R.id.article).tag == name }
            if (!loaded) android.os.SystemClock.sleep(50)
        }
        assertTrue("Document did not load: $name", loaded)
    }

    @Test fun launchesAndRecreatesWithSelectedDocument() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDocument(scenario)
            scenario.onActivity { activity ->
                val picker = activity.findViewById<MaterialAutoCompleteTextView>(R.id.document_picker)
                assertEquals("All widgets", picker.text.toString())
                assertTrue(activity.findViewById<TmarkView>(R.id.article).childCount > 0)
                val adapter = picker.adapter
                assertEquals(12, adapter.count)
                assertEquals("All widgets", adapter.getItem(0).toString())
                assertTrue((0 until adapter.count).all { !adapter.getItem(it).toString().endsWith(".tmark") })
                val menu = android.widget.ListView(activity).apply { this.adapter = adapter }
                picker.onItemClickListener?.onItemClick(menu, null, 1, 1)
            }
            awaitDocument(scenario, "edge_cases.tmark")
            scenario.onActivity { activity ->
                val picker = activity.findViewById<MaterialAutoCompleteTextView>(R.id.document_picker)
                assertEquals("Other edge cases", picker.text.toString())
                assertEquals("Other edge cases", picker.adapter.getItem(1).toString())
            }
            scenario.recreate()
            awaitDocument(scenario, "edge_cases.tmark")
            scenario.onActivity { activity ->
                assertEquals("Other edge cases", activity.findViewById<MaterialAutoCompleteTextView>(R.id.document_picker).text.toString())
                assertTrue(activity.findViewById<TmarkView>(R.id.article).childCount > 0)
            }
        }
    }
    @Test fun themeUsesMaterial3ColorsAndDocumentTitleIsHidden() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDocument(scenario)
            scenario.onActivity { activity ->
                val content = activity.findViewById<TmarkView>(R.id.article)
                content.render(app.tmark.Document(title = "Metadata title", content = listOf(app.tmark.Paragraph(listOf(app.tmark.TText("Body"))))))
                assertEquals(1, content.childCount)
                val body = content.getChildAt(0) as android.widget.TextView
                assertEquals("Body", body.text.toString())
                assertEquals(com.google.android.material.color.MaterialColors.getColor(content, com.google.android.material.R.attr.colorOnSurface), body.currentTextColor)
            }
        }
    }

    @Test fun asynchronouslyLoadedInlineIconReplacesItsAltText() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File.createTempFile("tmark-icon", ".png", context.cacheDir)
        try {
            val source = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            source.eraseColor(android.graphics.Color.MAGENTA)
            file.outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitDocument(scenario)
                scenario.onActivity { activity ->
                    activity.findViewById<TmarkView>(R.id.article).apply {
                        render(app.tmark.Document(content = listOf(app.tmark.Paragraph(listOf(app.tmark.Icon("image", "icon"))))),
                            app.tmark.android.MediaContentLoader { file.readBytes() })
                    }
                }
                var rendered = false
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (!rendered && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val text = activity.findViewById<TmarkView>(R.id.article).getChildAt(0) as android.widget.TextView
                        if (text.width > 0 && text.height > 0) {
                            val bitmap = android.graphics.Bitmap.createBitmap(text.width, text.height, android.graphics.Bitmap.Config.ARGB_8888)
                            text.draw(android.graphics.Canvas(bitmap))
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            rendered = pixels.any { it == android.graphics.Color.MAGENTA }
                            bitmap.recycle()
                        }
                    }
                    if (!rendered) android.os.SystemClock.sleep(50)
                }
                assertTrue("The inline icon did not replace its alt text", rendered)
            }
        } finally { file.delete() }
    }

    @Test fun revealingImageSpoilerKeepsFollowingContentInPlace() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File.createTempFile("tmark-spoiler", ".png", context.cacheDir)
        try {
            val source = android.graphics.Bitmap.createBitmap(713, 1100, android.graphics.Bitmap.Config.ARGB_8888)
            source.eraseColor(android.graphics.Color.MAGENTA)
            file.outputStream().use { source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitDocument(scenario)
                scenario.onActivity { activity ->
                    activity.findViewById<TmarkView>(R.id.article).apply {
                        render(app.tmark.Document(content = listOf(
                            app.tmark.ImageNode(src = "image", hasSpoiler = true),
                            app.tmark.Paragraph(listOf(app.tmark.TText("below"))),
                        )), app.tmark.android.MediaContentLoader { file.readBytes() })
                    }
                }
                var ready = false
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (!ready && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val content = activity.findViewById<TmarkView>(R.id.article)
                        val image = ((content.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup)
                            .getChildAt(0) as android.view.ViewGroup
                        ready = (image.getChildAt(0) as android.widget.ImageView).drawable != null && content.getChildAt(1).isLaidOut
                    }
                    if (!ready) android.os.SystemClock.sleep(50)
                }
                assertTrue("Spoiler preview did not load", ready)
                var before = 0
                scenario.onActivity { activity ->
                    val content = activity.findViewById<TmarkView>(R.id.article)
                    before = content.getChildAt(1).top
                    val image = ((content.getChildAt(0) as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup)
                        .getChildAt(0)
                    image.performClick()
                    assertTrue(image.contentDescription.toString() == "image")
                    assertTrue(!image.isClickable)
                }
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val content = activity.findViewById<TmarkView>(R.id.article)
                    assertEquals(before, content.getChildAt(1).top)
                }
            }
        } finally { file.delete() }
    }

}
