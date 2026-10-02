package app.tmark.android

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Runs on a background thread. Return the complete content for [url]. */
fun interface MediaContentLoader {
    fun getContent(url: String): ByteArray
}

/** The built-in loader accepts HTTP and HTTPS only. */
val HttpMediaContentLoader = MediaContentLoader { address ->
    var url = URL(address)
    require(url.protocol == "http" || url.protocol == "https") { "Unsupported media URL" }
    repeat(6) { redirect ->
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode in 300..399) {
                check(redirect < 5) { "Too many redirects" }
                url = URL(url, connection.getHeaderField("Location") ?: error("Missing redirect URL"))
                require(url.protocol == "http" || url.protocol == "https") { "Unsupported redirect" }
            } else {
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                return@MediaContentLoader connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 64 * 1024 * 1024) { "Media exceeds 64 MiB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } finally { connection.disconnect() }
    }
    error("Too many redirects")
}

internal enum class ImageFormat { JPEG, PNG, WEBP, AVIF, LOTTIE }

internal fun imageFormat(bytes: ByteArray): ImageFormat? = when {
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> ImageFormat.JPEG
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> ImageFormat.PNG
    bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" -> ImageFormat.WEBP
    isAvif(bytes) -> ImageFormat.AVIF
    isSelfContainedLottie(bytes) -> ImageFormat.LOTTIE
    else -> null
}

internal enum class AudioFormat { MP3, FLAC, OGG_OPUS, MP4_AAC_LC }

internal fun audioFormat(bytes: ByteArray): AudioFormat? = when {
    isMp3(bytes) -> AudioFormat.MP3
    bytes.size >= 4 && bytes.ascii(0, 4) == "fLaC" -> AudioFormat.FLAC
    bytes.size >= 36 && bytes.ascii(0, 4) == "OggS" && (bytes[26].toInt() and 255) > 0 &&
        (bytes[27].toInt() and 255) >= 8 && bytes.ascii(28, 8) == "OpusHead" -> AudioFormat.OGG_OPUS
    isAacLc(bytes) -> AudioFormat.MP4_AAC_LC
    else -> null
}

private fun ByteArray.ascii(offset: Int, length: Int) = String(this, offset, length, Charsets.US_ASCII)

private fun isSelfContainedLottie(bytes: ByteArray): Boolean = runCatching {
    val json = JSONObject(String(bytes, Charsets.UTF_8))
    if (json.optString("v").isEmpty() || !json.has("fr") || !json.has("ip") || !json.has("op") ||
        !json.has("w") || !json.has("h") || json.optJSONArray("layers") == null) return@runCatching false
    val assets = json.optJSONArray("assets") ?: return@runCatching !json.has("assets")
    (0 until assets.length()).all { index ->
        val asset = assets.optJSONObject(index) ?: return@all false
        val image = asset.optString("p")
        image.isEmpty() || image.startsWith("data:image/")
    }
}.getOrDefault(false)
private fun ByteArray.u8(index: Int) = this[index].toInt() and 255
private fun ByteArray.u32(index: Int): Long =
    (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or
        (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()

private fun isAvif(bytes: ByteArray): Boolean {
    if (bytes.size < 16 || bytes.ascii(4, 4) != "ftyp") return false
    val end = bytes.u32(0)
    if (end < 16 || end > bytes.size) return false
    for (offset in 8 until end.toInt() step 4) {
        if (offset == 12 || offset + 4 > end) continue
        if (bytes.ascii(offset, 4) in setOf("avif", "avis")) return true
    }
    return false
}

private fun isMp3(bytes: ByteArray): Boolean {
    var offset = 0
    if (bytes.size >= 3 && bytes.ascii(0, 3) == "ID3") {
        if (bytes.size < 10 || (6..9).any { bytes.u8(it) > 127 }) return false
        offset = 10 + (bytes.u8(6) shl 21) + (bytes.u8(7) shl 14) + (bytes.u8(8) shl 7) + bytes.u8(9)
        if (bytes.u8(5) and 0x10 != 0) offset += 10
        while (offset < bytes.size && bytes[offset] == 0.toByte()) offset++
    }
    if (offset < 0 || offset + 4 > bytes.size || bytes.u8(offset) != 255 || bytes.u8(offset + 1) and 0xe0 != 0xe0) return false
    val version = bytes.u8(offset + 1) shr 3 and 3
    val layer = bytes.u8(offset + 1) shr 1 and 3
    val bitrate = bytes.u8(offset + 2) shr 4 and 15
    val sampleRate = bytes.u8(offset + 2) shr 2 and 3
    return version != 1 && layer != 0 && bitrate != 0 && bitrate != 15 && sampleRate != 3
}

private fun isAacLc(bytes: ByteArray): Boolean = bytes.size >= 16 && bytes.ascii(4, 4) == "ftyp" &&
    bytes.u32(0) in 16..bytes.size.toLong() && hasAacLcEntry(bytes, bytes.u32(0).toInt(), bytes.size, 0)

private fun hasAacLcEntry(bytes: ByteArray, start: Int, end: Int, depth: Int): Boolean {
    if (depth > 16) return false
    var offset = start
    while (offset + 8 <= end) {
        val size = bytes.u32(offset)
        if (size < 8 || size > end - offset) return false
        val payload = offset + 8
        val boxEnd = offset + size.toInt()
        val type = bytes.ascii(offset + 4, 4)
        val nested = when (type) {
            "moov", "trak", "mdia", "minf", "stbl" -> payload
            "stsd" -> payload + 8
            "mp4a" -> payload + 28
            else -> -1
        }
        if (nested in payload..boxEnd && hasAacLcEntry(bytes, nested, boxEnd, depth + 1)) return true
        if (type == "esds" && payload + 4 <= boxEnd && hasAacLcConfig(bytes, payload + 4, boxEnd)) return true
        offset = boxEnd
    }
    return false
}

private fun hasAacLcConfig(bytes: ByteArray, start: Int, end: Int): Boolean {
    for (index in start until end - 2) {
        if (bytes.u8(index) != 5) continue
        var length = 0
        var cursor = index + 1
        for (step in 0..3) {
            if (cursor >= end) break
            val value = bytes.u8(cursor++)
            length = (length shl 7) or (value and 127)
            if (value and 128 == 0) break
        }
        if (length < 2 || length > end - cursor || bytes.u8(cursor) shr 3 != 2) continue
        var hasSbr = false
        for (bit in 0..length * 8 - 17) {
            if (readBits(bytes, cursor, bit, 11) == 0x2b7 &&
                readBits(bytes, cursor, bit + 11, 5) == 5 && readBits(bytes, cursor, bit + 16, 1) == 1) {
                hasSbr = true
                break
            }
        }
        if (!hasSbr) return true
    }
    return false
}

private fun readBits(bytes: ByteArray, offset: Int, bit: Int, count: Int): Int {
    var value = 0
    for (index in 0 until count) {
        val position = bit + index
        value = (value shl 1) or (bytes.u8(offset + position / 8) shr (7 - position % 8) and 1)
    }
    return value
}

internal fun writeMediaFile(context: Context, bytes: ByteArray): File =
    File.createTempFile("tmark-media-", ".bin", context.cacheDir).apply { outputStream().use { it.write(bytes) } }
