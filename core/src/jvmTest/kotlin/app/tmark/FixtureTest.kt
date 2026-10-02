package app.tmark

import java.io.File
import kotlin.test.*

class FixtureTest {
    private fun fixtures(folder: String) = File(checkNotNull(javaClass.getResource("/$folder")).toURI())
        .listFiles()!!.filter { it.extension in setOf("tmark", "fixture") }.also { assertTrue(it.isNotEmpty()) }

    @Test fun validFixturesMatchGoByteForByte() {
        for (file in fixtures("valid")) {
            // Some example files end with a newline outside the root value.
            val source = file.readText().removeSuffix("\n")
            val value = Tmark.decode(source, soft = file.extension == "tmark")
            assertEquals(source, Tmark.encode(value), file.name)
        }
    }
    @Test fun invalidFixturesAreRejected() {
        for (file in fixtures("invalid")) {
            assertFailsWith<TmarkException>(file.name) { Tmark.decodeDocument(file.readText()) }
        }
    }
}
