package mihon.core.archive

import android.system.Os
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

// KMK -->
/**
 * Random access to an archive's bytes.
 */
internal interface RandomAccessSource {
    val size: Long

    /**
     * Reads up to [into].remaining() bytes at [offset], advancing [into] by the number of bytes
     * actually read. Returns that number, or a value <= 0 when nothing could be read.
     */
    fun read(offset: Long, into: ByteBuffer): Int
}

/** [RandomAccessSource] over the file descriptor the reader holds. */
internal class FileDescriptorSource(
    private val fd: FileDescriptor,
    override val size: Long,
) : RandomAccessSource {
    override fun read(offset: Long, into: ByteBuffer): Int {
        var total = 0
        while (into.hasRemaining()) {
            val read = try {
                Os.pread(fd, into, offset + total)
            } catch (_: Exception) {
                return total
            }
            if (read <= 0) break
            total += read
        }
        return total
    }
}

/** Reads exactly [length] bytes at [offset], or null when that is not possible. */
internal fun RandomAccessSource.readBytes(offset: Long, length: Int): ByteArray? {
    if (length < 0 || offset < 0 || offset + length > size) return null

    // os.pread() takes a direct buffer, which is what the rest of this module uses as well.
    val buffer = ByteBuffer.allocateDirect(length)
    var total = 0
    while (buffer.hasRemaining()) {
        val read = read(offset + total, buffer)
        if (read <= 0) return null
        total += read
    }
    buffer.flip()

    val bytes = ByteArray(length)
    buffer.get(bytes)
    return bytes
}

internal class ZipEntryLocation(
    val method: Int,
    val flags: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
)

/**
 * The central directory of a ZIP archive.
 *
 * [ArchiveReader] can only move forward through libarchive, one entry header at a time, which turns
 * reading a chapter into O(n^2) header reads whenever the entries are not stored in page order (in
 * practice they often are not). A ZIP keeps an index of all of its entries at the end of the file,
 * so an entry can instead be read straight from its offset: one small local header read plus the
 * entry data, per page, no matter what order the pages are requested in.
 *
 * [parse] returns null for anything that is not a plain single disk ZIP - ZIP64 and spanned archives
 * fall back to libarchive together with the other archive formats. [open] returns null for entries
 * this class does not handle (encrypted, compressed with something other than deflate, damaged), so
 * callers can fall back for those as well.
 */
internal class ZipDirectory private constructor(
    private val source: RandomAccessSource,
    private val entries: Map<String, ZipEntryLocation>,
) {
    /** Returns the contents of the entry, or null when it should be read with libarchive instead. */
    fun open(name: String): InputStream? {
        val entry = entries[name] ?: return null
        if (entry.flags and FLAG_ENCRYPTED != 0) return null
        if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATED) return null

        // The local header repeats the name and may carry a different extra field than the central
        // directory entry, so the data offset can only be found by reading it.
        val header = source.readBytes(entry.localHeaderOffset, LOCAL_HEADER_SIZE) ?: return null
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != LOCAL_HEADER_SIGNATURE) return null

        val nameLength = buffer.getShort(NAME_LENGTH_OFFSET).toInt() and 0xFFFF
        val extraLength = buffer.getShort(EXTRA_LENGTH_OFFSET).toInt() and 0xFFFF
        val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength

        return when (entry.method) {
            METHOD_STORED -> {
                if (entry.uncompressedSize > Int.MAX_VALUE) return null
                source.readBytes(dataOffset, entry.uncompressedSize.toInt())?.inputStream()
            }

            METHOD_DEFLATED -> {
                if (entry.compressedSize > Int.MAX_VALUE) return null
                val compressed = source.readBytes(dataOffset, entry.compressedSize.toInt()) ?: return null
                InflaterInputStream(ByteArrayInputStream(compressed), Inflater(true))
            }

            else -> null
        }
    }

    companion object {
        private const val EOCD_SIGNATURE = 0x06054b50
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50

        private const val EOCD_SIZE = 22
        private const val EOCD_MAX_COMMENT = 0xFFFF
        private const val CENTRAL_HEADER_SIZE = 46
        private const val LOCAL_HEADER_SIZE = 30

        private const val NAME_LENGTH_OFFSET = 26
        private const val EXTRA_LENGTH_OFFSET = 28

        private const val FLAG_ENCRYPTED = 0x1
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        private const val UINT16_SENTINEL = 0xFFFF
        private const val UINT32_SENTINEL = 0xFFFFFFFFL

        fun parse(source: RandomAccessSource): ZipDirectory? {
            if (source.size < EOCD_SIZE) return null

            val tailLength = minOf(source.size, (EOCD_SIZE + EOCD_MAX_COMMENT).toLong()).toInt()
            val tail = source.readBytes(source.size - tailLength, tailLength) ?: return null
            val eocdOffset = findEocd(tail) ?: return null

            val eocd = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val entryCount = eocd.getShort(eocdOffset + 10).toInt() and 0xFFFF
            val centralSize = eocd.getInt(eocdOffset + 12).toLong() and 0xFFFFFFFFL
            val centralOffset = eocd.getInt(eocdOffset + 16).toLong() and 0xFFFFFFFFL

            // ZIP64 and spanned archives are left to libarchive.
            if (entryCount == UINT16_SENTINEL || centralSize == UINT32_SENTINEL || centralOffset == UINT32_SENTINEL) {
                return null
            }
            if (centralSize > Int.MAX_VALUE) return null
            if (centralOffset + centralSize > source.size) return null

            val central = source.readBytes(centralOffset, centralSize.toInt()) ?: return null
            val buffer = ByteBuffer.wrap(central).order(ByteOrder.LITTLE_ENDIAN)
            val entries = LinkedHashMap<String, ZipEntryLocation>(entryCount)

            repeat(entryCount) {
                if (buffer.remaining() < CENTRAL_HEADER_SIZE) return null
                if (buffer.getInt() != CENTRAL_HEADER_SIGNATURE) return null

                buffer.position(buffer.position() + 4) // version made by, version needed
                val flags = buffer.getShort().toInt() and 0xFFFF
                val method = buffer.getShort().toInt() and 0xFFFF
                buffer.position(buffer.position() + 8) // modification time, date and CRC-32
                val compressedSize = buffer.getInt().toLong() and 0xFFFFFFFFL
                val uncompressedSize = buffer.getInt().toLong() and 0xFFFFFFFFL
                val nameLength = buffer.getShort().toInt() and 0xFFFF
                val extraLength = buffer.getShort().toInt() and 0xFFFF
                val commentLength = buffer.getShort().toInt() and 0xFFFF
                buffer.position(buffer.position() + 8) // disk number, internal and external attributes
                val localHeaderOffset = buffer.getInt().toLong() and 0xFFFFFFFFL

                if (buffer.remaining() < nameLength + extraLength + commentLength) return null
                val name = String(central, buffer.position(), nameLength, Charsets.UTF_8)
                buffer.position(buffer.position() + nameLength + extraLength + commentLength)

                entries[name] = ZipEntryLocation(
                    method = method,
                    flags = flags,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset,
                )
            }

            return ZipDirectory(source, entries)
        }

        private fun findEocd(tail: ByteArray): Int? {
            for (offset in tail.size - EOCD_SIZE downTo 0) {
                if (tail[offset].toInt() != (EOCD_SIGNATURE and 0xFF)) continue
                if (tail[offset + 1].toInt() != ((EOCD_SIGNATURE ushr 8) and 0xFF)) continue
                if (tail[offset + 2].toInt() != ((EOCD_SIGNATURE ushr 16) and 0xFF)) continue
                if (tail[offset + 3].toInt() != ((EOCD_SIGNATURE ushr 24) and 0xFF)) continue
                return offset
            }
            return null
        }
    }
}
// KMK <--
