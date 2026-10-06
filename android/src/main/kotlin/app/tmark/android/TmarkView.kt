package app.tmark.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.tmark.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.R as MaterialR

/** Native Android document content. Place in a ScrollView or an existing scrolling screen. */
class TmarkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var config: TmarkConfig = TmarkConfig()
        set(value) { field = value; rebuild() }
    private var document: Document? = null
    private var contentLoader: MediaContentLoader = HttpMediaContentLoader
    private var state = Bundle()
    private val anchors = mutableMapOf<String, View>()
    private val references = mutableMapOf<String, View>()
    private val reveals = mutableMapOf<View, () -> Unit>()
    private var composeOwner: CarouselViewTreeOwner? = null
    private var composeOwnerRoot: View? = null
    private var addedLifecycleOwner = false
    private var addedSavedStateOwner = false
    private val textColorValue get() = color(MaterialR.attr.colorOnSurface)
    private val accent get() = color(androidx.appcompat.R.attr.colorPrimary)

    init { orientation = VERTICAL }

    override fun onAttachedToWindow() {
        val root = rootView
        addedLifecycleOwner = root.findViewTreeLifecycleOwner() == null
        addedSavedStateOwner = root.findViewTreeSavedStateRegistryOwner() == null
        if (addedLifecycleOwner || addedSavedStateOwner) {
            CarouselViewTreeOwner().also {
                composeOwner = it
                composeOwnerRoot = root
                if (addedLifecycleOwner) root.setViewTreeLifecycleOwner(it)
                if (addedSavedStateOwner) root.setViewTreeSavedStateRegistryOwner(it)
                it.start()
            }
        }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        composeOwner?.stop()
        composeOwner = null
        if (addedLifecycleOwner) composeOwnerRoot?.setViewTreeLifecycleOwner(null)
        if (addedSavedStateOwner) composeOwnerRoot?.setViewTreeSavedStateRegistryOwner(null)
        composeOwnerRoot = null
        addedLifecycleOwner = false
        addedSavedStateOwner = false
    }

    /** Parse outside the UI thread for large documents, then call [render] on the UI thread. */
    fun render(source: String, soft: Boolean = false, contentLoader: MediaContentLoader = HttpMediaContentLoader) =
        render(config.codec.decodeDocument(source, soft), contentLoader)
    fun render(source: String, contentLoader: MediaContentLoader) = render(source, false, contentLoader)
    fun render(value: Document, contentLoader: MediaContentLoader = HttpMediaContentLoader) {
        if (document != value) state = Bundle()
        document = value
        this.contentLoader = contentLoader
        rebuild()
    }

    private fun rebuild() {
        removeAllViews(); anchors.clear(); references.clear(); reveals.clear()
        val doc = document ?: return
        if (config.showDocumentHeader) {
            addView(rich(listOf(TText(doc.title)), "title", MaterialR.attr.textAppearanceHeadlineMedium))
            doc.description?.let { addView(centeredRich(listOf(TText(it)), "description")) }
        }
        appendBlocks(this, doc.content, "body")
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("super", super.onSaveInstanceState()); putBundle("tmark", state)
    }
    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(saved: Parcelable?) {
        if (saved is Bundle) {
            super.onRestoreInstanceState(saved.getParcelable("super"))
            state = saved.getBundle("tmark") ?: Bundle(); rebuild()
        } else super.onRestoreInstanceState(saved)
    }

    private fun appendBlocks(parent: LinearLayout, blocks: RichBlocks, path: String) {
        var i = 0
        while (i < blocks.size) {
            val node = blocks[i]
            val view = if (node is Details && config.blockRenderer == null) detailsCard().apply {
                do {
                    addView(details(blocks[i] as Details, "$path/$i"))
                    i++
                } while (i < blocks.size && blocks[i] is Details)
            } else {
                i++
                block(node, "$path/${i - 1}")
            }
            parent.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun block(node: BlockNode, path: String): View {
        config.widgets.block(context, node)?.let { return it }
        config.blockRenderer?.invoke(context, node)?.let { return it }
        return when (node) {
            is Paragraph -> rich(node.children, path)
            is Header -> rich(node.children, path, intArrayOf(
                MaterialR.attr.textAppearanceHeadlineLarge,
                MaterialR.attr.textAppearanceHeadlineMedium,
                MaterialR.attr.textAppearanceHeadlineSmall,
                MaterialR.attr.textAppearanceTitleLarge,
                MaterialR.attr.textAppearanceTitleMedium,
                MaterialR.attr.textAppearanceTitleSmall,
            )[(node.size.takeIf { it in 1..6 } ?: 4) - 1]).apply {
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }
            is Preformatted -> copyable(node.text, path, node.language ?: context.getString(R.string.tmark_code), styled = true)
            is MathBlock -> copyable(node.expression, path)
            is Anchor -> View(context).apply { minimumHeight = 1; anchors[node.name] = this }
            Divider -> View(context).apply { minimumHeight = dp(1); setBackgroundColor(color(MaterialR.attr.colorOutlineVariant)) }
            is Blockquote -> column().apply {
                setPadding(dp(16), dp(8), dp(8), dp(8)); background = outline()
                appendBlocks(this, node.blocks, path); if (node.credit.isNotEmpty()) addView(centeredRich(node.credit, "$path/credit", MaterialR.attr.textAppearanceBodySmall))
            }
            is PullQuote -> column().apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(rich(node.text, path, MaterialR.attr.textAppearanceHeadlineSmall).apply {
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                })
                if (node.credit.isNotEmpty()) addView(centeredRich(node.credit, "$path/credit", MaterialR.attr.textAppearanceBodySmall).apply {
                    setTextColor(color(MaterialR.attr.colorOnSurfaceVariant))
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            is ListBlock -> column().apply {
                var next = 1
                val ordered = node.items.any { it.order != null || it.type != null }
                node.items.forEachIndexed { i, item ->
                    val number = item.order ?: next
                    if (ordered) next = number + 1
                    addView(row().apply {
                        gravity = Gravity.TOP
                        val marker = if (item.checked != null) MaterialCheckBox(context).apply {
                            isChecked = state.getBoolean("$path/$i/check", item.checked == true)
                            contentDescription = plain(item.children)
                            isEnabled = config.onTaskChange != null
                            setOnCheckedChangeListener { _, checked -> state.putBoolean("$path/$i/check", checked); config.onTaskChange?.invoke(item, checked) }
                        } else MaterialTextView(context).apply {
                            text = if (ordered) "${listLabel(number, item.type)}." else "•"
                            gravity = Gravity.END
                            setPadding(0, dp(8), dp(8), 0)
                        }
                        addView(marker, LayoutParams(dp(48), LayoutParams.WRAP_CONTENT))
                        addView(column().apply {
                            setPadding(0, dp(if (item.checked != null) 12 else 8), 0, 0)
                            appendBlocks(this, item.children, "$path/$i")
                        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    })
                }
            }
            is Details -> detailsCard().apply { addView(details(node, path)) }
            is Table -> table(node, path)
            is MapBlock -> column().apply {
                addView(MaterialButton(context).apply {
                    text = context.getString(R.string.tmark_open_map, node.lat.toString(), node.lon.toString())
                    setOnClickListener { open(Uri.parse("geo:${node.lat},${node.lon}?q=${node.lat},${node.lon}" + (node.zoom?.let { "&z=$it" } ?: ""))) }
                }); caption(this, node.caption, path)
            }
            is ImageNode -> media(node, node.src, node.caption, node.hasSpoiler, path)
            is VideoNode -> media(node, node.src, node.caption, node.hasSpoiler, path)
            is AudioNode -> media(node, node.src, node.caption, false, path)
            is Collage -> column().apply {
                val ratios = node.children.mapIndexed { index, child -> when (node.children.size) {
                    1, 2 -> 1.0
                    3 -> if (index == 0) 1.6 else 1.0
                    4 -> if (index == 0) 1.4 else 1.0
                    else -> if (child is VideoNode) 16.0 / 9 else 1.0
                } }
                val layout = GroupedMediaLayout(context, ratios)
                node.children.forEachIndexed { i, child ->
                    layout.addView(galleryTile(child, "$path/$i") { width, height -> layout.setRatio(i, width.toDouble() / height) })
                }
                addView(layout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                caption(this, node.caption, path)
            }
            is Slideshow -> slideshow(node, path)
            is Unknown -> copyable(node.raw, path)
            else -> copyable(config.codec.encode(node), path)
        }
    }

    private fun copyable(value: String, path: String, label: String = context.getString(R.string.tmark_code), styled: Boolean = false): View = FrameLayout(context).apply {
        minimumHeight = dp(48)
        if (styled) {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(color(MaterialR.attr.colorSurfaceVariant))
                setStroke(dp(1), color(MaterialR.attr.colorOutlineVariant))
            }
            clipToOutline = true
        }
        addView(HorizontalScrollView(context).apply {
            addView(rich(listOf(Code(value)), path).apply { setPadding(if (styled) dp(12) else 0, dp(8), 0, dp(8)) })
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(48) })
        addView(MaterialButton(context, null, MaterialR.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.tmark_copy)
            iconTint = ColorStateList.valueOf(accent)
            contentDescription = context.getString(R.string.tmark_copy)
            if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = contentDescription
            setOnClickListener {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(label, value))
            }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
    }

    private fun detailsCard() = column().apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(color(MaterialR.attr.colorSurface))
        }
        clipToOutline = true
        foreground = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(dp(1), color(MaterialR.attr.colorOutlineVariant))
        }
    }

    private fun details(node: Details, path: String): View = column().apply {
        val children = column().apply {
            setPadding(dp(16), dp(16), dp(16), dp(6))
            appendBlocks(this, node.children, path)
        }
        val label = node.summary.ifEmpty { listOf(TText(context.getString(R.string.tmark_details))) }
        val header = row().apply {
            minimumHeight = dp(48)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            isClickable = true
            isFocusable = true
            foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                try { it.getDrawable(0) } finally { it.recycle() }
            }
        }
        val title = rich(label, "$path/summary").apply { setTextIsSelectable(false) }
        val chevron = ImageView(context).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.tmark_expand_more))
            imageTintList = ColorStateList.valueOf(color(MaterialR.attr.colorOnSurfaceVariant))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(chevron, LayoutParams(dp(24), dp(24)).apply { marginStart = dp(16) })
        header.contentDescription = title.text
        fun update() {
            val expanded = state.getBoolean("$path/open", node.isOpen)
            children.visibility = if (expanded) VISIBLE else GONE
            header.setBackgroundColor(color(if (expanded) MaterialR.attr.colorSurfaceVariant else MaterialR.attr.colorSurface))
            chevron.rotation = if (expanded) 180f else 0f
            if (android.os.Build.VERSION.SDK_INT >= 30) header.stateDescription = context.getString(if (expanded) R.string.tmark_expanded else R.string.tmark_collapsed)
        }
        reveals[children] = { state.putBoolean("$path/open", true); update() }
        val toggle = View.OnClickListener {
            state.putBoolean("$path/open", !state.getBoolean("$path/open", node.isOpen))
            update()
        }
        header.setOnClickListener(toggle)
        title.setOnClickListener(toggle)
        addView(header); addView(children); update()
    }

    private fun slideshow(node: Slideshow, path: String): View = column().apply {
        if (node.children.isNotEmpty()) {
            addView(SlideshowSizeFrame(context, node.children.map { when (it) {
                is ImageNode -> it.src
                is VideoNode -> it.preview
                else -> null
            } }, contentLoader).apply {
                val sizeFrame = this
                addView(ComposeView(context).apply {
                    setContent {
                        SlideshowPager(
                            count = node.children.size,
                            initialItem = state.getInt("$path/page", 0).coerceIn(node.children.indices),
                            onItemChange = { state.putInt("$path/page", it) },
                            tile = { index -> galleryTile(node.children[index], "$path/$index", cropImage = false) { width, height ->
                                sizeFrame.setImageSize(index, width, height)
                            } },
                        )
                    }
                }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        caption(this, node.caption, path)
    }

    private fun gallery(node: GalleryNode, path: String): View =
        if (node is BlockNode) block(node, path) else copyable(config.codec.encode(node), path)

    private fun galleryTile(node: GalleryNode, path: String, cropImage: Boolean = true,
                            onImageSize: (Int, Int) -> Unit): View {
        if (node is BlockNode) config.blockRenderer?.invoke(context, node)?.let { return it }
        return FrameLayout(context).apply {
            setBackgroundColor(color(MaterialR.attr.colorSurfaceVariant))
            clipChildren = true
            val tile = this
            when (node) {
                is ImageNode -> {
                    addView(imageView(node, path, true, onImageSize, cropImage), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }
                is VideoNode -> {
                    fun showVideo() {
                        tile.removeAllViews()
                        tile.addView(NativeVideoView(context, node.src, contentLoader, node.preview, node.loop, config.onError, true, onImageSize, cropImage), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    }
                    if (node.hasSpoiler && !state.getBoolean("$path/revealed")) {
                        addView(MaterialButton(context).apply {
                            text = context.getString(R.string.tmark_show_spoiler)
                            setOnClickListener { state.putBoolean("$path/revealed", true); showVideo() }
                        }, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    } else showVideo()
                }
                else -> addView(gallery(node, path), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        }
    }

    private fun imageView(node: ImageNode, path: String, fillBounds: Boolean = false,
                          onImageSize: ((Int, Int) -> Unit)? = null, cropToBounds: Boolean = fillBounds): NativeImageView =
        NativeImageView(context, node.src, contentLoader, config.onError, node.hasSpoiler && !state.getBoolean("$path/revealed"), fillBounds, onImageSize, cropToBounds).apply {
            val description = node.caption?.text?.let { inline(it, "$path/alt", null).toString() } ?: node.src
            contentDescription = if (isSpoilerHidden) context.getString(R.string.tmark_show_spoiler) else description
            if (isSpoilerHidden) setOnClickListener {
                state.putBoolean("$path/revealed", true)
                revealSpoiler()
                contentDescription = description
                setOnClickListener(null)
                isClickable = false
            }
        }

    private fun media(node: BlockNode, src: String, cap: Caption?, spoiler: Boolean, path: String): View = column().apply {
        val holder = column()
        fun load() {
            holder.removeAllViews()
            holder.addView(when {
                node is ImageNode -> imageView(node, path)
                node is VideoNode -> NativeVideoView(context, src, contentLoader, node.preview, node.loop, config.onError)
                else -> NativeAudioView(context, src, contentLoader, config.onError)
            })
        }
        if (spoiler && node !is ImageNode && !state.getBoolean("$path/revealed")) {
            holder.addView(MaterialButton(context).apply {
                text = context.getString(R.string.tmark_show_spoiler)
                setOnClickListener { state.putBoolean("$path/revealed", true); load() }
            })
        } else load()
        addView(holder); caption(this, cap, path)
    }

    private fun caption(parent: LinearLayout, caption: Caption?, path: String) {
        caption ?: return
        if (caption.text.isNotEmpty()) parent.addView(centeredRich(caption.text, "$path/caption", MaterialR.attr.textAppearanceBodySmall))
        if (caption.credit.isNotEmpty()) parent.addView(centeredRich(caption.credit, "$path/credit", MaterialR.attr.textAppearanceLabelSmall))
    }

    private fun centeredRich(nodes: RichText, path: String, appearance: Int = MaterialR.attr.textAppearanceBodyLarge): TextView =
        rich(nodes, path, appearance).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private fun rich(nodes: RichText, path: String, appearance: Int = MaterialR.attr.textAppearanceBodyLarge): TextView = MaterialTextView(context).apply {
        setTextAppearance(context.obtainStyledAttributes(intArrayOf(appearance)).let {
            try { it.getResourceId(0, 0) } finally { it.recycle() }
        })
        setTextColor(textColorValue)
        setLinkTextColor(accent)
        setTextIsSelectable(true)
        text = inline(nodes, path, this)
        movementMethod = LinkMovementMethod.getInstance()
    }

    private fun inline(nodes: RichText, path: String, owner: TextView?): CharSequence {
        val out = SpannableStringBuilder()
        nodes.forEachIndexed { i, node ->
            val key = "$path/$i"
            if (owner != null) config.widgets.inline(context, node, owner)?.let { widget ->
                val start = out.length
                out.append((node as? Math)?.expression ?: "\uFFFC")
                out.setSpan(InlineWidgetSpan(widget, owner, start), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                return@forEachIndexed
            }
            val override = config.inlineRenderer?.invoke(context, node)
            if (override != null) { out.append(override); return@forEachIndexed }
            val start = out.length
            fun children(c: RichText) { out.append(inline(c, key, owner)) }
            fun span(s: Any) { if (start < out.length) out.setSpan(s, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            fun click(action: () -> Unit) { span(object : ClickableSpan() {
                override fun onClick(widget: View) = action()
                override fun updateDrawState(ds: TextPaint) { ds.color = accent; ds.isUnderlineText = true }
            }) }
            when (node) {
                is TText -> out.append(node.text)
                is Bold -> { children(node.children); span(StyleSpan(Typeface.BOLD)) }
                is Italic -> { children(node.children); span(StyleSpan(Typeface.ITALIC)) }
                is Marked -> { children(node.children); span(BackgroundColorSpan(color(MaterialR.attr.colorSecondaryContainer))) }
                is Underline -> { children(node.children); span(UnderlineSpan()) }
                is Strikethrough -> { children(node.children); span(StrikethroughSpan()) }
                is Subscript -> { children(node.children); span(SubscriptSpan()); span(RelativeSizeSpan(0.75f)) }
                is Superscript -> { children(node.children); span(SuperscriptSpan()); span(RelativeSizeSpan(0.75f)) }
                is Spoiler -> {
                    if (state.getBoolean(key)) children(node.children) else {
                        val mask = textColorValue
                        out.append(inline(node.children, key, null).toString())
                        span(BackgroundColorSpan(mask))
                        span(ForegroundColorSpan(mask))
                        span(object : ClickableSpan() {
                            override fun onClick(widget: View) { state.putBoolean(key, true); rebuild() }
                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = mask
                                ds.bgColor = mask
                                ds.isUnderlineText = false
                            }
                        })
                    }
                }
                is Link -> { children(node.children); click { open(Uri.parse(node.href)) } }
                is AnchorLink -> { children(node.children); click { navigate(node.anchorName, anchors) } }
                is ReferenceLink -> { children(node.children); click { navigate(node.referenceName, references) } }
                is Reference -> { children(node.children); if (owner != null) references[node.name] = owner }
                is Code -> { out.append(node.text); span(TypefaceSpan("monospace")) }
                is Math -> { out.append(node.expression); span(TypefaceSpan("monospace")) }
                is DateTimeNode -> out.append(runCatching { config.formatDateTime(node) }.getOrElse { config.onError?.invoke(it); node.unix.toString() })
                is Icon -> {
                    val size = owner?.textSize?.toInt() ?: dp(18)
                    val fallback = "[${node.alternativeText?.takeIf { it.isNotBlank() } ?: context.getString(R.string.tmark_icon)}]"
                    out.append(fallback)
                    if (owner != null) {
                        span(NativeIconSpan(owner, node.src, contentLoader, size, config.onError))
                    }
                }
                is Unknown -> { out.append(node.raw); span(TypefaceSpan("monospace")) }
                else -> { out.append(config.codec.encode(node)); span(TypefaceSpan("monospace")) }
            }
        }
        return out
    }

    private fun navigate(name: String, targets: Map<String, View>) {
        config.onAnchor?.let { it(name); return }
        val target = targets[name] ?: return
        var ancestor: View? = target
        while (ancestor != null) { reveals[ancestor]?.invoke(); ancestor = ancestor.parent as? View }
        target.post { target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), false); target.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
    }

    private fun open(uri: Uri) {
        config.onLink?.let { it(uri); return }
        if (uri.scheme !in setOf("https", "http", "mailto", "tel", "geo", "content", "android.resource")) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (error: Exception) { config.onError?.invoke(error); Toast.makeText(context, R.string.tmark_media_unavailable, Toast.LENGTH_SHORT).show() }
    }

    private fun table(node: Table, path: String): View = column().apply {
        if (node.caption.isNotEmpty()) addView(centeredRich(node.caption, "$path/caption", MaterialR.attr.textAppearanceBodySmall))
        val placements = placeCells(node.rows)
        val grid = GridLayout(context).apply {
            columnCount = placements.maxOfOrNull { it.column + it.columnSpan } ?: 1
            rowCount = placements.maxOfOrNull { it.row + it.rowSpan } ?: 1
        }
        placements.forEach { p ->
            val cell = p.cell
            val view = rich(cell.children, "$path/${p.row}/${p.column}").apply {
                setPadding(dp(12), dp(8), dp(12), dp(8)); minWidth = dp(100); maxWidth = dp(360 * p.columnSpan.coerceAtMost(3))
                if (cell.isHeader) { setTypeface(typeface, Typeface.BOLD); if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
                gravity = (when (cell.align) { TableCellAlign.center -> Gravity.CENTER_HORIZONTAL; TableCellAlign.right -> Gravity.RIGHT; else -> Gravity.LEFT }) or
                    (when (cell.valign) { TableCellValign.middle -> Gravity.CENTER_VERTICAL; TableCellValign.bottom -> Gravity.BOTTOM; else -> Gravity.TOP })
                background = GradientDrawable().apply {
                    setColor(if (node.striped && p.row % 2 == 1) color(MaterialR.attr.colorSurfaceVariant) else color(MaterialR.attr.colorSurface))
                    if (node.bordered) setStroke(dp(1), color(MaterialR.attr.colorOutlineVariant))
                }
            }
            grid.addView(view, GridLayout.LayoutParams(GridLayout.spec(p.row, p.rowSpan, GridLayout.FILL), GridLayout.spec(p.column, p.columnSpan, GridLayout.FILL)))
        }
        addView(HorizontalScrollView(context).apply { addView(grid) })
    }
    private fun plain(blocks: RichBlocks): String = blocks.filterIsInstance<Paragraph>().joinToString(" ") { inline(it.children, "label", null).toString() }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun color(attribute: Int) = MaterialColors.getColor(this, attribute)
    private fun outline() = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color(MaterialR.attr.colorSurfaceVariant))
        setStroke(dp(1), color(MaterialR.attr.colorOutlineVariant))
    }
}
