package app.tmark.example

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.*
import app.tmark.Document
import app.tmark.Tmark
import app.tmark.android.TmarkConfig
import app.tmark.android.TmarkView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class ExampleChoice(val index: Int, val title: String) {
        override fun toString() = title
    }

    private val loader = Executors.newFixedThreadPool(4)
    private val documents = mutableMapOf<String, Document>()
    private var request = 0
    private var selectedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        val examples = listOf(
            "all_widgets.tmark", "edge_cases.tmark", "edge_empties.tmark",
            "inline_formatting.tmark", "lists_and_tasks.tmark", "maps_and_events.tmark",
            "math_and_code.tmark", "media_formats.tmark", "media_gallery.tmark",
            "quotes_and_details.tmark", "references_and_anchors.tmark", "tables.tmark",
        )
        val content = TmarkView(this).apply {
            id = R.id.article
            config = TmarkConfig(widgets = RaTeXWidgets, onError = { android.util.Log.e("TMark", "Media loading failed", it) })
            val inset = (16 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, inset)
        }
        val titles = arrayOfNulls<String>(examples.size)
        var documentAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            examples.indices.map { ExampleChoice(it, "Loading document ${it + 1}…") }.toMutableList())
        val picker = MaterialAutoCompleteTextView(this).apply {
            id = R.id.document_picker
            setAdapter(documentAdapter)
            inputType = 0
        }
        val pickerLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle).apply {
            hint = "Document"
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            addView(picker)
        }
        val status = MaterialTextView(this)
        val retry = MaterialButton(this).apply { text = "Retry"; visibility = View.GONE }
        val savedIndex = (savedInstanceState?.getInt("selected", 0) ?: 0).coerceIn(examples.indices)
        fun fetch(name: String): Document {
            val connection = URL("https://tmark.waus.app/examples/$name").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
                val source = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return Tmark.decodeDocument(source, soft = true)
            } finally {
                connection.disconnect()
            }
        }
        fun remember(index: Int, document: Document) {
            documents[examples[index]] = document
            titles[index] = document.title.ifBlank { "Document ${index + 1}" }
            documentAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                titles.mapIndexed { position, title ->
                    ExampleChoice(position, title ?: "Loading document ${position + 1}…")
                }.toMutableList())
            picker.setAdapter(documentAdapter)
            if (index == selectedIndex) picker.setText(titles[index], false)
        }
        fun show(index: Int) {
            if (index !in examples.indices) return
            selectedIndex = index
            picker.setText(titles[index].orEmpty(), false)
            val name = examples[index]
            val currentRequest = ++request
            content.tag = null
            content.visibility = View.GONE
            retry.visibility = View.GONE
            status.visibility = View.VISIBLE
            status.text = "Loading $name…"
            fun display(document: Document) {
                content.render(document)
                content.tag = name
                content.visibility = View.VISIBLE
                status.visibility = View.GONE
            }
            documents[name]?.let { display(it); return }
            loader.execute {
                val result = runCatching { fetch(name) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    result.onSuccess { document ->
                        remember(index, document)
                        if (currentRequest == request) display(document)
                    }.onFailure { error ->
                        if (currentRequest != request) return@onFailure
                        android.util.Log.e("TMark", "Failed to load $name", error)
                        status.text = "Could not load $name: ${error.message}"
                        retry.visibility = View.VISIBLE
                    }
                }
            }
        }
        retry.setOnClickListener { show(selectedIndex) }
        picker.setOnItemClickListener { parent, _, position, _ ->
            val choice = parent.getItemAtPosition(position) as ExampleChoice
            if (choice.index != selectedIndex) show(choice.index)
        }
        val scroll = ScrollView(this).apply { id = R.id.article_scroll; addView(content) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            val inset = (16 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset, inset, 0)
            addView(pickerLayout); addView(status); addView(retry); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        show(savedIndex)
        examples.indices.filter { it != savedIndex }.forEach { index ->
            loader.execute {
                runCatching { fetch(examples[index]) }.onSuccess { document ->
                    runOnUiThread { if (!isDestroyed) remember(index, document) }
                }
            }
        }
    }
    override fun onDestroy() {
        request++
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected", selectedIndex.coerceAtLeast(0))
        super.onSaveInstanceState(outState)
    }
}
