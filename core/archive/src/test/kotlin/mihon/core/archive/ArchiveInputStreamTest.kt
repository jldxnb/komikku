package mihon.core.archive

import io.kotest.matchers.shouldBe
import me.zhanghai.android.libarchive.Archive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * Contract tests for [ArchiveInputStream], run on the JVM against the `Archive` test double in the
 * `me.zhanghai.android.libarchive` test source package (see that file for why the native binding
 * cannot be used here).
 *
 * The double reproduces the real `readData`: it writes up to `buffer.remaining()` bytes and advances
 * the buffer position, leaving the limit untouched. Everything the input stream does around that
 * call is what these tests pin down.
 */
class ArchiveInputStreamTest {

    private var consumed = 0

    @AfterEach
    fun resetDouble() {
        Archive.readDataHandler = null
        consumed = 0
    }

    private fun archiveStreamOf(payload: ByteArray): ArchiveInputStream {
        Archive.readDataHandler = { buffer ->
            val count = minOf(buffer.remaining(), payload.size - consumed)
            if (count > 0) {
                buffer.put(payload, consumed, count)
                consumed += count
            }
        }
        return ArchiveInputStream(0L, payload.size.toLong(), false)
    }

    @Test
    fun `readInto writes at the requested offset`() {
        val payload = ByteArray(64) { it.toByte() }
        val stream = archiveStreamOf(payload)

        val destination = ByteArray(64)
        val read = stream.read(destination, 16, 8)

        read shouldBe 8
        destination.copyOfRange(0, 16) shouldBe ByteArray(16)
        destination.copyOfRange(16, 24) shouldBe payload.copyOfRange(0, 8)
    }

    @Test
    fun `readInto does not write past the requested window`() {
        val payload = ByteArray(64) { (it + 1).toByte() }
        val stream = archiveStreamOf(payload)

        val destination = ByteArray(64)
        stream.read(destination, 4, 4)

        destination.copyOfRange(8, 64) shouldBe ByteArray(56)
    }

    @Test
    fun `zero length read returns zero and consumes nothing`() {
        val stream = archiveStreamOf(ByteArray(8) { it.toByte() })

        val destination = ByteArray(8)
        stream.read(destination, 0, 0) shouldBe 0
        stream.read(destination, 0, 8) shouldBe 8
    }

    @Test
    fun `readInto returns the number of bytes read rather than end of stream`() {
        val stream = archiveStreamOf(ByteArray(32) { it.toByte() })

        val destination = ByteArray(32)
        stream.read(destination, 0, 32) shouldBe 32
        stream.read(destination, 0, 32) shouldBe -1
    }

    @Test
    fun `readInto returns a short count for the final chunk`() {
        val stream = archiveStreamOf(ByteArray(10))

        stream.read(ByteArray(32), 0, 32) shouldBe 10
    }

    @Test
    fun `single byte reads walk the entry and then stop`() {
        val stream = archiveStreamOf(byteArrayOf(0x2A, 0x2B))

        stream.read() shouldBe 0x2A
        stream.read() shouldBe 0x2B
        stream.read() shouldBe -1
    }
}
