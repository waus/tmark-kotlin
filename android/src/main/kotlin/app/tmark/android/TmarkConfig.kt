package app.tmark.android

import android.content.Context
import android.net.Uri
import android.view.View
import app.tmark.*
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/** Hooks are invoked on the main thread. Return null to use the built-in renderer. */
data class TmarkConfig(
    val codec: TmarkCodec = TmarkCodec(),
    val blockRenderer: ((Context, BlockNode) -> View?)? = null,
    val inlineRenderer: ((Context, RichNode) -> CharSequence?)? = null,
    /** Typed native widgets supplied by optional renderer modules. */
    val widgets: TmarkWidgets = TmarkWidgets.Empty,
    val onLink: ((Uri) -> Unit)? = null,
    val onAnchor: ((String) -> Unit)? = null,
    val onTaskChange: ((ListItem, Boolean) -> Unit)? = null,
    val onError: ((Throwable) -> Unit)? = null,
    val formatDateTime: (DateTimeNode) -> String = { node ->
        DateFormat.getDateTimeInstance().apply {
            if (node.timezone.isNotEmpty()) timeZone = TimeZone.getTimeZone(node.timezone)
        }.format(Date(node.unix.also { require(it in Long.MIN_VALUE / 1000..Long.MAX_VALUE / 1000) }.times(1000)))
    },
    val showDocumentHeader: Boolean = false,
)
