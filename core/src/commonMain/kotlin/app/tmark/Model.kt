package app.tmark

/** Wire nesting limit shared with the Go implementation. */
const val TMARK_MAX_DEPTH = 16
interface TmarkValue
interface RichNode : TmarkValue
interface BlockNode : TmarkValue
interface GalleryNode : TmarkValue
typealias RichText = List<RichNode>
typealias RichBlocks = List<BlockNode>
data class TText(val text: String) : RichNode
/** An unrecognized node preserved verbatim by soft decoding. */
data class Unknown(val raw: String) : RichNode, BlockNode
data class Caption(val text: RichText = emptyList(), val credit: RichText = emptyList()) : TmarkValue {
    companion object : NodeType<Caption>(Caption::class, "caption") {
        val credit = field("credit", FieldTypes.richText, Caption::credit) { emptyList() }
        val text = content(FieldTypes.richText, Caption::text) { emptyList() }
        override fun create(values: FieldValues) = Caption(text = values[text], credit = values[credit])
    }
}
enum class TableCellAlign { left, center, right }
enum class TableCellValign { top, middle, bottom }

data class Document(
    val url: String = "",
    val title: String = "",
    val description: String? = null,
    val authorName: String? = null,
    val authorUrl: String? = null,
    val imageUrl: String? = null,
    val attachedMedia: List<AttachedMedia> = emptyList(),
    val content: RichBlocks = emptyList()
) : TmarkValue {
    companion object : NodeType<Document>(Document::class, "document", TmarkLayout.EXPANDED) {
        val url = field("url", FieldTypes.string, Document::url) { "" }
        val title = field("title", FieldTypes.string, Document::title) { "" }
        val description = optional("description", FieldTypes.string, Document::description)
        val authorName = optional("author_name", FieldTypes.string, Document::authorName)
        val authorUrl = optional("author_url", FieldTypes.string, Document::authorUrl)
        val imageUrl = optional("image_url", FieldTypes.string, Document::imageUrl)
        val attachedMedia = field("attached_media", FieldTypes.nodes<AttachedMedia>(), Document::attachedMedia) { emptyList() }
        val content = content(FieldTypes.blocks, Document::content) { emptyList() }
        override fun create(values: FieldValues) = Document(
            url = values[url],
            title = values[title],
            description = values[description],
            authorName = values[authorName],
            authorUrl = values[authorUrl],
            imageUrl = values[imageUrl],
            attachedMedia = values[attachedMedia],
            content = values[content],
        )
    }
}

data class AttachedMedia(
    val hash: String = "",
    val content: RichBlocks = emptyList()
) : TmarkValue {
    companion object : NodeType<AttachedMedia>(AttachedMedia::class, "attached", TmarkLayout.EXPANDED) {
        val hash = field("hash", FieldTypes.string, AttachedMedia::hash) { "" }
        val content = content(FieldTypes.blocks, AttachedMedia::content) { emptyList() }
        override fun create(values: FieldValues) = AttachedMedia(
            hash = values[hash],
            content = values[content],
        )
    }
}

data class Link(
    val href: String = "",
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Link>(Link::class, "a") {
        val href = field("href", FieldTypes.string, Link::href) { "" }
        val children = content(FieldTypes.richText, Link::children) { emptyList() }
        override fun create(values: FieldValues) = Link(
            href = values[href],
            children = values[children],
        )
    }
}

data class AnchorLink(
    val anchorName: String = "",
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<AnchorLink>(AnchorLink::class, "anchor-link") {
        val anchorName = field("name", FieldTypes.string, AnchorLink::anchorName) { "" }
        val children = content(FieldTypes.richText, AnchorLink::children) { emptyList() }
        override fun create(values: FieldValues) = AnchorLink(
            anchorName = values[anchorName],
            children = values[children],
        )
    }
}

data class Reference(
    val name: String = "",
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Reference>(Reference::class, "ref") {
        val name = field("name", FieldTypes.string, Reference::name) { "" }
        val children = content(FieldTypes.richText, Reference::children) { emptyList() }
        override fun create(values: FieldValues) = Reference(
            name = values[name],
            children = values[children],
        )
    }
}

data class ReferenceLink(
    val referenceName: String = "",
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<ReferenceLink>(ReferenceLink::class, "ref-link") {
        val referenceName = field("name", FieldTypes.string, ReferenceLink::referenceName) { "" }
        val children = content(FieldTypes.richText, ReferenceLink::children) { emptyList() }
        override fun create(values: FieldValues) = ReferenceLink(
            referenceName = values[referenceName],
            children = values[children],
        )
    }
}

data class Bold(
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Bold>(Bold::class, "b") {
        val children = content(FieldTypes.richText, Bold::children) { emptyList() }
        override fun create(values: FieldValues) = Bold(
            children = values[children],
        )
    }
}

data class Italic(
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Italic>(Italic::class, "i") {
        val children = content(FieldTypes.richText, Italic::children) { emptyList() }
        override fun create(values: FieldValues) = Italic(
            children = values[children],
        )
    }
}

data class Marked(
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Marked>(Marked::class, "m") {
        val children = content(FieldTypes.richText, Marked::children) { emptyList() }
        override fun create(values: FieldValues) = Marked(
            children = values[children],
        )
    }
}

data class Underline(
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Underline>(Underline::class, "u") {
        val children = content(FieldTypes.richText, Underline::children) { emptyList() }
        override fun create(values: FieldValues) = Underline(
            children = values[children],
        )
    }
}

data class Strikethrough(
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Strikethrough>(Strikethrough::class, "s") {
        val children = content(FieldTypes.richText, Strikethrough::children) { emptyList() }
        override fun create(values: FieldValues) = Strikethrough(
            children = values[children],
        )
    }
}

data class Spoiler(
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Spoiler>(Spoiler::class, "spoiler") {
        val children = content(FieldTypes.richText, Spoiler::children) { emptyList() }
        override fun create(values: FieldValues) = Spoiler(
            children = values[children],
        )
    }
}

data class Subscript(
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Subscript>(Subscript::class, "sub") {
        val children = content(FieldTypes.richText, Subscript::children) { emptyList() }
        override fun create(values: FieldValues) = Subscript(
            children = values[children],
        )
    }
}

data class Superscript(
    val children: RichText = emptyList()
) : RichNode {
    companion object : NodeType<Superscript>(Superscript::class, "sup") {
        val children = content(FieldTypes.richText, Superscript::children) { emptyList() }
        override fun create(values: FieldValues) = Superscript(
            children = values[children],
        )
    }
}

data class DateTimeNode(
    val unix: Long = 0,
    val timezone: String = ""
) : RichNode {
    companion object : NodeType<DateTimeNode>(DateTimeNode::class, "datetime") {
        val timezone = field("timezone", FieldTypes.string, DateTimeNode::timezone) { "" }
        val unix = content(FieldTypes.long, DateTimeNode::unix) { 0 }
        override fun create(values: FieldValues) = DateTimeNode(
            timezone = values[timezone],
            unix = values[unix],
        )
    }
}

data class Code(
    val text: String = ""
) : RichNode {
    companion object : NodeType<Code>(Code::class, "code") {
        val text = content(FieldTypes.string, Code::text) { "" }
        override fun create(values: FieldValues) = Code(
            text = values[text],
        )
    }
}

data class Math(
    val expression: String = ""
) : RichNode {
    companion object : NodeType<Math>(Math::class, "math") {
        val expression = content(FieldTypes.string, Math::expression) { "" }
        override fun create(values: FieldValues) = Math(
            expression = values[expression],
        )
    }
}

data class Icon(
    val src: String = "",
    val alternativeText: String? = null
) : RichNode {
    companion object : NodeType<Icon>(Icon::class, "icon") {
        val alternativeText = optional("alt", FieldTypes.string, Icon::alternativeText)
        val src = content(FieldTypes.string, Icon::src) { "" }
        override fun create(values: FieldValues) = Icon(
            alternativeText = values[alternativeText],
            src = values[src],
        )
    }
}

data class Paragraph(
    val children: RichText = emptyList()
) : BlockNode {
    companion object : NodeType<Paragraph>(Paragraph::class, "p") {
        val children = content(FieldTypes.richText, Paragraph::children) { emptyList() }
        override fun create(values: FieldValues) = Paragraph(
            children = values[children],
        )
    }
}

data class Header(
    val size: Int = 4,
    val children: RichText = emptyList()
) : BlockNode {
    companion object : NodeType<Header>(Header::class, "h") {
        val size = field("s", FieldTypes.int, Header::size)
        val children = content(FieldTypes.richText, Header::children) { emptyList() }
        override fun create(values: FieldValues) = Header(
            size = values[size],
            children = values[children],
        )
    }
}

data class Preformatted(
    val text: String = "",
    val language: String? = null
) : BlockNode {
    companion object : NodeType<Preformatted>(Preformatted::class, "pre", TmarkLayout.EXPANDED) {
        val language = optional("language", FieldTypes.string, Preformatted::language)
        val text = content(FieldTypes.string, Preformatted::text) { "" }
        override fun create(values: FieldValues) = Preformatted(
            language = values[language],
            text = values[text],
        )
    }
}

data class MathBlock(
    val expression: String = ""
) : BlockNode {
    companion object : NodeType<MathBlock>(MathBlock::class, "math-block", TmarkLayout.EXPANDED) {
        val expression = content(FieldTypes.string, MathBlock::expression) { "" }
        override fun create(values: FieldValues) = MathBlock(
            expression = values[expression],
        )
    }
}

data class Anchor(
    val name: String = ""
) : BlockNode {
    companion object : NodeType<Anchor>(Anchor::class, "anchor") {
        val name = content(FieldTypes.string, Anchor::name) { "" }
        override fun create(values: FieldValues) = Anchor(
            name = values[name],
        )
    }
}

data object Divider : BlockNode {
    val type: NodeType<Divider> = object : NodeType<Divider>(Divider::class, "hr") {
        override fun create(values: FieldValues) = Divider
    }
}

data class Blockquote(
    val blocks: RichBlocks = emptyList(),
    val credit: RichText = emptyList()
) : BlockNode {
    companion object : NodeType<Blockquote>(Blockquote::class, "q", TmarkLayout.WHEN_MULTIPLE) {
        val credit = field("credit", FieldTypes.richText, Blockquote::credit) { emptyList() }
        val blocks = content(FieldTypes.blocks, Blockquote::blocks) { emptyList() }
        override fun create(values: FieldValues) = Blockquote(
            credit = values[credit],
            blocks = values[blocks],
        )
    }
}

data class PullQuote(
    val text: RichText = emptyList(),
    val credit: RichText = emptyList()
) : BlockNode {
    companion object : NodeType<PullQuote>(PullQuote::class, "as") {
        val credit = field("credit", FieldTypes.richText, PullQuote::credit) { emptyList() }
        val text = content(FieldTypes.richText, PullQuote::text) { emptyList() }
        override fun create(values: FieldValues) = PullQuote(
            credit = values[credit],
            text = values[text],
        )
    }
}

data class Collage(
    val children: List<GalleryNode> = emptyList(),
    val caption: Caption? = null
) : BlockNode {
    companion object : NodeType<Collage>(Collage::class, "collage", TmarkLayout.EXPANDED) {
        val caption = optional("caption", FieldTypes.record(Caption), Collage::caption)
        val children = content(FieldTypes.nodes<GalleryNode>(), Collage::children) { emptyList() }
        override fun create(values: FieldValues) = Collage(
            caption = values[caption],
            children = values[children],
        )
    }
}

data class Slideshow(
    val children: List<GalleryNode> = emptyList(),
    val caption: Caption? = null
) : BlockNode {
    companion object : NodeType<Slideshow>(Slideshow::class, "slideshow", TmarkLayout.EXPANDED) {
        val caption = optional("caption", FieldTypes.record(Caption), Slideshow::caption)
        val children = content(FieldTypes.nodes<GalleryNode>(), Slideshow::children) { emptyList() }
        override fun create(values: FieldValues) = Slideshow(
            caption = values[caption],
            children = values[children],
        )
    }
}

data class ListBlock(
    val items: List<ListItem> = emptyList()
) : BlockNode {
    companion object : NodeType<ListBlock>(ListBlock::class, "list", TmarkLayout.EXPANDED) {
        val items = content(FieldTypes.nodes<ListItem>(), ListBlock::items) { emptyList() }
        override fun create(values: FieldValues) = ListBlock(
            items = values[items],
        )
    }
}

data class ListItem(
    val children: RichBlocks = emptyList(),
    val type: String? = null,
    val order: Int? = null,
    val checked: Boolean? = null
) : BlockNode {
    companion object : NodeType<ListItem>(ListItem::class, "li", TmarkLayout.WHEN_MULTIPLE) {
        val type = optional("type", FieldTypes.string, ListItem::type)
        val order = optional("order", FieldTypes.int, ListItem::order)
        val checked = optional("checked", FieldTypes.boolean, ListItem::checked)
        val children = content(FieldTypes.blocks, ListItem::children) { emptyList() }
        override fun create(values: FieldValues) = ListItem(
            type = values[type],
            order = values[order],
            checked = values[checked],
            children = values[children],
        )
    }
}

data class MapBlock(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val zoom: Int? = null,
    val caption: Caption? = null
) : BlockNode {
    companion object : NodeType<MapBlock>(MapBlock::class, "map") {
        val lat = field("lat", FieldTypes.double, MapBlock::lat) { 0.0 }
        val lon = field("lon", FieldTypes.double, MapBlock::lon) { 0.0 }
        val zoom = optional("zoom", FieldTypes.int, MapBlock::zoom)
        val caption = optional("caption", FieldTypes.record(Caption), MapBlock::caption)
        override fun create(values: FieldValues) = MapBlock(
            lat = values[lat],
            lon = values[lon],
            zoom = values[zoom],
            caption = values[caption],
        )
    }
}

data class ImageNode(
    val src: String = "",
    val caption: Caption? = null,
    val hasSpoiler: Boolean = false
) : BlockNode, GalleryNode {
    companion object : NodeType<ImageNode>(ImageNode::class, "img") {
        val caption = optional("caption", FieldTypes.record(Caption), ImageNode::caption)
        val hasSpoiler = field("has_spoiler", FieldTypes.boolean, ImageNode::hasSpoiler) { false }
        val src = content(FieldTypes.string, ImageNode::src) { "" }
        override fun create(values: FieldValues) = ImageNode(
            caption = values[caption],
            hasSpoiler = values[hasSpoiler],
            src = values[src],
        )
    }
}

data class VideoNode(
    val preview: String,
    val src: String = "",
    val caption: Caption? = null,
    val hasSpoiler: Boolean = false,
    val loop: Boolean = false
) : BlockNode, GalleryNode {
    companion object : NodeType<VideoNode>(VideoNode::class, "video") {
        val caption = optional("caption", FieldTypes.record(Caption), VideoNode::caption)
        val hasSpoiler = field("has_spoiler", FieldTypes.boolean, VideoNode::hasSpoiler) { false }
        val preview = field("preview", FieldTypes.string, VideoNode::preview)
        val loop = field("loop", FieldTypes.boolean, VideoNode::loop) { false }
        val src = content(FieldTypes.string, VideoNode::src) { "" }
        override fun create(values: FieldValues) = VideoNode(
            caption = values[caption],
            hasSpoiler = values[hasSpoiler],
            preview = values[preview],
            loop = values[loop],
            src = values[src],
        )
    }
}

data class AudioNode(
    val src: String = "",
    val caption: Caption? = null
) : BlockNode {
    companion object : NodeType<AudioNode>(AudioNode::class, "audio") {
        val caption = optional("caption", FieldTypes.record(Caption), AudioNode::caption)
        val src = content(FieldTypes.string, AudioNode::src) { "" }
        override fun create(values: FieldValues) = AudioNode(
            caption = values[caption],
            src = values[src],
        )
    }
}

data class Table(
    val rows: List<TableRow> = emptyList(),
    val caption: RichText = emptyList(),
    val bordered: Boolean = false,
    val striped: Boolean = false
) : BlockNode {
    companion object : NodeType<Table>(Table::class, "table", TmarkLayout.EXPANDED) {
        val caption = field("caption", FieldTypes.richText, Table::caption) { emptyList() }
        val bordered = field("bordered", FieldTypes.boolean, Table::bordered) { false }
        val striped = field("striped", FieldTypes.boolean, Table::striped) { false }
        val rows = content(FieldTypes.nodes<TableRow>(), Table::rows) { emptyList() }
        override fun create(values: FieldValues) = Table(
            caption = values[caption],
            bordered = values[bordered],
            striped = values[striped],
            rows = values[rows],
        )
    }
}

data class TableRow(val cells: List<Cell> = emptyList()) : BlockNode {
    companion object : NodeType<TableRow>(TableRow::class, "tr") {
        val cells = content(FieldTypes.nodes<Cell>(), TableRow::cells) { emptyList() }
        override fun create(values: FieldValues) = TableRow(values[cells])
    }
}

data class Cell(
    val children: RichText = emptyList(),
    val isHeader: Boolean = false,
    val colspan: Int? = null,
    val rowspan: Int? = null,
    val align: TableCellAlign? = null,
    val valign: TableCellValign? = null
) : BlockNode {
    companion object : NodeType<Cell>(Cell::class, "td") {
        val isHeader = field("header", FieldTypes.boolean, Cell::isHeader) { false }
        val colspan = optional("colspan", FieldTypes.int, Cell::colspan)
        val rowspan = optional("rowspan", FieldTypes.int, Cell::rowspan)
        val align = optional("align", FieldTypes.enum(TableCellAlign.entries), Cell::align)
        val valign = optional("valign", FieldTypes.enum(TableCellValign.entries), Cell::valign)
        val children = content(FieldTypes.richText, Cell::children) { emptyList() }
        override fun create(values: FieldValues) = Cell(
            isHeader = values[isHeader],
            colspan = values[colspan],
            rowspan = values[rowspan],
            align = values[align],
            valign = values[valign],
            children = values[children],
        )
    }
}

data class Details(
    val children: RichBlocks = emptyList(),
    val summary: RichText = emptyList(),
    val isOpen: Boolean = false
) : BlockNode {
    companion object : NodeType<Details>(Details::class, "details", TmarkLayout.WHEN_MULTIPLE) {
        val summary = field("summary", FieldTypes.richText, Details::summary) { emptyList() }
        val isOpen = field("open", FieldTypes.boolean, Details::isOpen) { false }
        val children = content(FieldTypes.blocks, Details::children) { emptyList() }
        override fun create(values: FieldValues) = Details(
            summary = values[summary],
            isOpen = values[isOpen],
            children = values[children],
        )
    }
}
