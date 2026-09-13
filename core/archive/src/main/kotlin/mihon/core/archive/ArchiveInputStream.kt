package mihon.core.archive

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import mihon.core.archive.ArchiveEntry as MihonArchiveEntry

class ArchiveInputStream(
    buffer: Long,
    size: Long,
    // SY -->
    encrypted: Boolean,
    // SY <--
) : InputStream() {
    private val lock = Any()

    @Volatile
    private var isClosed = false

    private val archive = Archive.readNew()

    init {
        try {
            // SY -->
            if (encrypted) {
                Archive.readAddPassphrase(archive, CbzCrypto.getDecryptedPasswordCbz())
            }
            // SY <--
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray())
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            Archive.readOpenMemoryUnsafe(archive, buffer, size)
        } catch (e: ArchiveException) {
            close()
            throw e
        }
    }

    private val oneByteBuffer = ByteBuffer.allocateDirect(1)

    override fun read(): Int {
        // KMK -->
        // This direct buffer is reused across reads, so it is rewound and flipped here rather than
        // inside read(), which has to leave the caller's window alone.
        oneByteBuffer.clear()
        // KMK <--
        read(oneByteBuffer)
        // KMK -->
        oneByteBuffer.flip()
        // KMK <--
        return if (oneByteBuffer.hasRemaining()) oneByteBuffer.get().toUByte().toInt() else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        // KMK -->
        if (len == 0) return 0
        // KMK <--
        val buffer = ByteBuffer.wrap(b, off, len)
        read(buffer)
        // KMK -->
        // flip() would move position back to 0, so the count is taken from how far libarchive
        // advanced it past the caller's offset instead of from remaining().
        return if (buffer.position() > off) buffer.position() - off else -1
        // KMK <--
    }

    private fun read(buffer: ByteBuffer) {
        // KMK -->
        // No clear() and no flip() in here on purpose. The JNI binding of Archive.readData takes
        // the write address from `position` and the writable size from `limit - position`, then
        // advances `position` by the byte count. clear() resets that window to the whole array, so
        // a read(b, off, len) would be written at the start of b, spill past off + len and even
        // report the array length as the byte count, and a zero length read would still consume
        // data. The buffer is therefore handed over exactly as the caller set it up.
        // KMK <--
        Archive.readData(archive, buffer)
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
        }

        Archive.readFree(archive)
    }

    fun getNextEntry(): MihonArchiveEntry? {
        return Archive.readNextHeader(archive).takeUnless { it == 0L }?.let { entry ->
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.decodeToString() ?: return null
            val isFile = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG
            // SY -->
            val isEncrypted = ArchiveEntry.isEncrypted(entry)
            // SY <--
            MihonArchiveEntry(
                name,
                isFile,
                // SY -->
                isEncrypted,
                // SY <--
            )
        }
    }
}
