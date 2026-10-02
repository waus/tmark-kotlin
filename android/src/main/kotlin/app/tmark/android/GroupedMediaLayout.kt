package app.tmark.android

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt

/** Port of the grouped media geometry used by the Dart and Swift renderers. */
internal class GroupedMediaLayout(context: Context, initialRatios: List<Double>) : ViewGroup(context) {
    private val ratios = initialRatios.toMutableList()
    private val spacing = (2 * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    private val minWidth = 96 * resources.displayMetrics.density
    private var tiles: List<MediaTile> = emptyList()

    fun setRatio(index: Int, ratio: Double) {
        if (index !in ratios.indices) return
        val normalized = ratio.coerceIn(0.2, 5.0)
        if (abs(ratios[index] - normalized) < 0.001) return
        ratios[index] = normalized
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: minWidth.roundToInt()
        tiles = MediaGeometry(ratios, width.toDouble(), minWidth.toDouble(), spacing.toDouble()).layout()
        children().forEachIndexed { index, child ->
            val tile = tiles[index]
            child.measure(
                View.MeasureSpec.makeMeasureSpec(tile.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(tile.height, View.MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, tiles.maxOfOrNull { it.top + it.height } ?: 0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        children().forEachIndexed { index, child ->
            val tile = tiles[index]
            child.layout(tile.left, tile.top, tile.left + tile.width, tile.top + tile.height)
        }
    }

    private fun children() = (0 until childCount).map(::getChildAt)
}

internal data class MediaTile(val left: Int, val top: Int, val width: Int, val height: Int)

internal class MediaGeometry(
    private val ratios: List<Double>,
    private val maxWidth: Double,
    private val minWidth: Double,
    private val spacing: Double,
) {
    private val count get() = ratios.size
    private val maxHeight get() = maxWidth
    private val averageRatio get() = (1.0 + ratios.sum()) / count
    private val proportions get() = ratios.joinToString("") { if (it > 1.2) "w" else if (it < 0.8) "n" else "q" }

    fun layout(): List<MediaTile> = when (count) {
        0 -> emptyList()
        1 -> listOf(tile(0.0, 0.0, maxWidth, maxWidth / ratios[0]))
        2 -> layoutTwo()
        3 -> layoutThree()
        4 -> layoutFour()
        else -> layoutComplex()
    }

    private fun layoutTwo(): List<MediaTile> {
        if (proportions == "ww" && averageRatio > 1.4 && abs(ratios[1] - ratios[0]) < 0.2) {
            val height = minOf(maxWidth / ratios[0], maxWidth / ratios[1], (maxHeight - spacing) / 2)
            return listOf(tile(0.0, 0.0, maxWidth, height), tile(0.0, height + spacing, maxWidth, height))
        }
        if (proportions == "ww" || proportions == "qq") {
            val width = (maxWidth - spacing) / 2
            val height = minOf(width / ratios[0], width / ratios[1], maxHeight)
            return listOf(tile(0.0, 0.0, width, height), tile(width + spacing, 0.0, width, height))
        }
        val secondWidth = minOf(
            maxOf(0.4 * (maxWidth - spacing), (maxWidth - spacing) / ratios[0] / (1 / ratios[0] + 1 / ratios[1])),
            maxWidth - spacing - minWidth * 1.5,
        )
        val firstWidth = maxWidth - secondWidth - spacing
        val height = minOf(maxHeight, firstWidth / ratios[0], secondWidth / ratios[1])
        return listOf(tile(0.0, 0.0, firstWidth, height), tile(firstWidth + spacing, 0.0, secondWidth, height))
    }

    private fun layoutThree(): List<MediaTile> {
        if (proportions.first() == 'n') {
            val firstHeight = maxHeight
            val thirdHeight = minOf((maxHeight - spacing) / 2, ratios[1] * (maxWidth - spacing) / (ratios[2] + ratios[1]))
            val secondHeight = firstHeight - thirdHeight - spacing
            val rightWidth = maxOf(minWidth, minOf((maxWidth - spacing) / 2, thirdHeight * ratios[2], secondHeight * ratios[1]))
            val leftWidth = minOf(firstHeight * ratios[0], maxWidth - spacing - rightWidth)
            return listOf(
                tile(0.0, 0.0, leftWidth, firstHeight),
                tile(leftWidth + spacing, 0.0, rightWidth, secondHeight),
                tile(leftWidth + spacing, secondHeight + spacing, rightWidth, thirdHeight),
            )
        }
        val firstHeight = minOf(maxWidth / ratios[0], (maxHeight - spacing) * 0.66)
        val secondWidth = (maxWidth - spacing) / 2
        val secondHeight = minOf(maxHeight - firstHeight - spacing, secondWidth / ratios[1], secondWidth / ratios[2])
        val thirdWidth = maxWidth - secondWidth - spacing
        return listOf(
            tile(0.0, 0.0, maxWidth, firstHeight),
            tile(0.0, firstHeight + spacing, secondWidth, secondHeight),
            tile(secondWidth + spacing, firstHeight + spacing, thirdWidth, secondHeight),
        )
    }

    private fun layoutFour(): List<MediaTile> {
        if (proportions.first() == 'w') {
            val firstHeight = minOf(maxWidth / ratios[0], (maxHeight - spacing) * 0.66)
            val height = (maxWidth - 2 * spacing) / (ratios[1] + ratios[2] + ratios[3])
            val firstWidth = maxOf(minWidth, minOf((maxWidth - 2 * spacing) * 0.4, height * ratios[1]))
            val thirdWidth = maxOf(minWidth, (maxWidth - 2 * spacing) * 0.33, height * ratios[3])
            val secondWidth = maxWidth - firstWidth - thirdWidth - 2 * spacing
            val rowHeight = minOf(maxHeight - firstHeight - spacing, height)
            return listOf(
                tile(0.0, 0.0, maxWidth, firstHeight),
                tile(0.0, firstHeight + spacing, firstWidth, rowHeight),
                tile(firstWidth + spacing, firstHeight + spacing, secondWidth, rowHeight),
                tile(firstWidth + secondWidth + 2 * spacing, firstHeight + spacing, thirdWidth, rowHeight),
            )
        }
        val leftWidth = minOf(maxHeight * ratios[0], (maxWidth - spacing) * 0.6)
        val width = (maxHeight - 2 * spacing) / (1 / ratios[1] + 1 / ratios[2] + 1 / ratios[3])
        val firstHeight = width / ratios[1]
        val secondHeight = width / ratios[2]
        val thirdHeight = maxHeight - firstHeight - secondHeight - 2 * spacing
        val rightWidth = maxOf(minWidth, minOf(maxWidth - leftWidth - spacing, width))
        return listOf(
            tile(0.0, 0.0, leftWidth, maxHeight),
            tile(leftWidth + spacing, 0.0, rightWidth, firstHeight),
            tile(leftWidth + spacing, firstHeight + spacing, rightWidth, secondHeight),
            tile(leftWidth + spacing, firstHeight + secondHeight + 2 * spacing, rightWidth, thirdHeight),
        )
    }

    private data class Attempt(val counts: List<Int>, val heights: List<Double>)

    private fun layoutComplex(): List<MediaTile> {
        val cropped = ratios.map { if (averageRatio > 1.1) it.coerceIn(1.0, 2.75) else it.coerceIn(0.6667, 1.0) }
        val attempts = mutableListOf<Attempt>()
        fun push(counts: List<Int>) {
            var offset = 0
            val heights = counts.map { columns ->
                val sum = cropped.subList(offset, offset + columns).sum()
                offset += columns
                (maxWidth - (columns - 1) * spacing) / sum
            }
            attempts += Attempt(counts, heights)
        }
        for (first in 1 until count) {
            val second = count - first
            if (first <= 3 && second <= 3) push(listOf(first, second))
        }
        for (first in 1 until count - 1) for (second in 1 until count - first) {
            val third = count - first - second
            if (first <= 3 && second <= (if (averageRatio < 0.85) 4 else 3) && third <= 3) push(listOf(first, second, third))
        }
        for (first in 1 until count - 2) for (second in 1 until count - first - 1) for (third in 1 until count - first - second) {
            val fourth = count - first - second - third
            if (first <= 3 && second <= 3 && third <= 3 && fourth <= 3) push(listOf(first, second, third, fourth))
        }
        if (attempts.isEmpty()) {
            var remaining = count
            val counts = mutableListOf<Int>()
            while (remaining > 0) { val next = minOf(3, remaining); counts += next; remaining -= next }
            push(counts)
        }
        fun score(attempt: Attempt): Double {
            val totalHeight = attempt.heights.sum() + spacing * (attempt.counts.size - 1)
            val badHeight = if (attempt.heights.min() < minWidth) 1.5 else 1.0
            val badOrder = if (attempt.counts.zipWithNext().any { (a, b) -> a > b }) 1.5 else 1.0
            return abs(totalHeight - maxWidth * 4 / 3) * badHeight * badOrder
        }
        val best = attempts.minBy(::score)
        val result = mutableListOf<MediaTile>()
        var index = 0
        var y = 0.0
        best.counts.forEachIndexed { row, columns ->
            val rowHeight = best.heights[row]
            var x = 0.0
            repeat(columns) { column ->
                val width = if (column == columns - 1) maxWidth - x else cropped[index] * rowHeight
                result += tile(x, y, width, rowHeight)
                x += width + spacing
                index++
            }
            y += rowHeight + spacing
        }
        return result
    }

    private fun tile(x: Double, y: Double, width: Double, height: Double) = MediaTile(
        x.roundToInt(), y.roundToInt(), width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1),
    )
}
