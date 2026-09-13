package mihon.core.archive

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [RandomAccessSource] over an in-memory archive, which is what Android's file descriptor is not. */
private class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long = bytes.size.toLong()

    override fun read(offset: Long, into: ByteBuffer): Int {
        if (offset < 0 || offset >= size) return -1
        val count = minOf(into.remaining().toLong(), size - offset).toInt()
        into.put(bytes, offset.toInt(), count)
        return count
    }
}

class ZipDirectoryTest {

    @Test
    fun `Reads a stored entry`() {
        val payload = ByteArray(4096) { it.toByte() }

        val directory = parse("page-001.jpg" to payload, stored = true)

        directory.open("page-001.jpg")!!.readBytes() shouldBe payload
    }

    @Test
    fun `Reads a deflated entry`() {
        val payload = "deflate me ".repeat(200).toByteArray()

        val directory = parse("page-002.jpg" to payload)

        directory.open("page-002.jpg")!!.readBytes() shouldBe payload
    }

    @Test
    fun `Reads entries that were written out of page order`() {
        // This is what a real CBZ looks like: the archive order does not follow the page names, so
        // looking an entry up must not depend on how far a previous lookup walked.
        val directory = parse(
            "page-010.jpg" to "ten".toByteArray(),
            "page-002.jpg" to "two".toByteArray(),
            "page-001.jpg" to "one".toByteArray(),
            stored = true,
        )

        directory.open("page-001.jpg")!!.readBytes().decodeToString() shouldBe "one"
        directory.open("page-010.jpg")!!.readBytes().decodeToString() shouldBe "ten"
        directory.open("page-002.jpg")!!.readBytes().decodeToString() shouldBe "two"
    }

    @Test
    fun `Returns null for an entry it does not know`() {
        val directory = parse("page-001.jpg" to byteArrayOf(1, 2, 3))

        directory.open("missing.jpg").shouldBeNull()
    }

    @Test
    fun `Returns null when the bytes are not a zip`() {
        ZipDirectory.parse(ByteArraySource(ByteArray(4096) { 0x41 })).shouldBeNull()
    }

    private fun parse(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ZipDirectory {
        val zip = buildZip(entries.toList(), stored)
        return ZipDirectory.parse(ByteArraySource(zip)) ?: error("the test archive could not be parsed")
    }

    private fun buildZip(entries: List<Pair<String, ByteArray>>, stored: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, payload) ->
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = payload.size.toLong()
                    entry.compressedSize = payload.size.toLong()
                    entry.crc = CRC32().apply { update(payload) }.value
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
