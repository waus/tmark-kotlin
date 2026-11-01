package app.tmark.android

import android.content.Context
import android.net.Uri
import android.view.View
import app.tmark.*
import java.text.DateFormat
import java.util.SimpleTimeZone
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
        val zone = node.timezone.ifEmpty { "UTC" }
        val fixed = Regex("(?:([+-])([0-9]{2}):([0-9]{2})|UTC([+-])([0-9]{1,2})(?::([0-9]{2}))?)").matchEntire(zone)
        val sourceZone = when {
            zone in listOf("UTC", "GMT", "Z") -> SimpleTimeZone(0, zone)
            fixed != null -> {
                val values = fixed.groupValues
                val sign = values[1].ifEmpty { values[4] }
                val hours = values[2].ifEmpty { values[5] }.toInt()
                val minutes = values[3].ifEmpty { values[6] }.ifEmpty { "0" }.toInt()
                require(hours < 24 && minutes < 60) { "Unsupported timezone: $zone" }
                SimpleTimeZone((hours * 60 + minutes) * 60_000 * if (sign == "-") -1 else 1, zone)
            }
            else -> {
                require(zone !in listOf("Local", "localtime", "posixrules") &&
                    !zone.startsWith("posix/") && !zone.startsWith("right/") &&
                    !zone.startsWith("UTC+") && !zone.startsWith("UTC-") &&
                    !zone.startsWith("GMT+") && !zone.startsWith("GMT-") &&
                    (!Regex("[A-Za-z]{2,4}").matches(zone) || zone in listOf(
                        "CET", "Cuba", "EET", "EST", "Eire", "GB", "GMT", "HST", "Iran", "MET", "MST",
                        "NZ", "PRC", "ROC", "ROK", "UCT", "UTC", "WET", "Zulu")) &&
                    zone in TimeZone.getAvailableIDs()) { "Unsupported timezone: $zone" }
                TimeZone.getTimeZone(zone)
            }
        }
        DateFormat.getDateTimeInstance().apply {
            timeZone = sourceZone
        }.format(Date(node.unix.also { require(it in Long.MIN_VALUE / 1000..Long.MAX_VALUE / 1000) }.times(1000)))
    },
    val showDocumentHeader: Boolean = false,
)
