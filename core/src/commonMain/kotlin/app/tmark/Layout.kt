package app.tmark

/** Placement independent of UI toolkit; spans cannot overlap earlier row spans. */
data class CellPlacement(val cell: Cell, val row: Int, val column: Int, val rowSpan: Int, val columnSpan: Int)

fun placeCells(rows: List<TableRow>): List<CellPlacement> {
    val occupied = mutableSetOf<Pair<Int, Int>>()
    return buildList {
        rows.forEachIndexed { row, tableRow ->
            var column = 0
            tableRow.cells.forEach { cell ->
                val width = (cell.colspan ?: 1).coerceIn(1, 100)
                val height = (cell.rowspan ?: 1).coerceIn(1, 100)
                while ((column until column + width).any { row to it in occupied }) column++
                add(CellPlacement(cell, row, column, height, width))
                for (r in row until row + height) for (c in column until column + width) occupied += r to c
                column += width
            }
        }
    }
}

/** HTML-compatible decimal, alphabetic and Roman ordered list markers. */
fun listLabel(number: Int, type: String?): String {
    if (number <= 0) return number.toString()
    return when (type) {
        "a", "A" -> {
            var n = number
            val letters = buildString { while (n > 0) { n--; append(('a'.code + n % 26).toChar()); n /= 26 } }.reversed()
            if (type == "A") letters.uppercase() else letters
        }
        "i", "I" -> {
            if (number > 3999) return number.toString()
            var n = number
            val roman = buildString {
                for ((value, symbol) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
                    while (n >= value) { append(symbol); n -= value }
                }
            }
            if (type == "i") roman.lowercase() else roman
        }
        else -> number.toString()
    }
}
