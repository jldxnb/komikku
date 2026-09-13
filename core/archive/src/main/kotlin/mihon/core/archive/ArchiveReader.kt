package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    val size = pfd.statSize
    val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    // KMK -->
    // Declared before the SY init block below on purpose: opening a password protected archive
    // reads an entry from within checkEncryptionStatus(), while the object is still constructed,
    // so the session state has to be initialized by then.

    /**
     * The archive's file descriptor, kept open so that [zipDirectory] can read entries directly.
     * [close] releases it together with the mapping.
     */
    private val pfd = pfd

    /**
     * Entry index of a plain ZIP archive, when [ZipDirectory.parse] could read it. Set after the
     * encryption check, because encrypted archives have to go through libarchive.
     */
    private var zipDirectory: ZipDirectory? = null

    /**
     * Names of the entries the read session has already moved past. Asking for one of them again
     * means the session has to restart from the first entry.
     */
    private val consumedEntries = mutableSetOf<String>()

    /**
     * Read session shared by [getInputStream] calls.
     *
     * libarchive can only move forward through an archive, and every [ArchiveInputStream] registers
     * all supported formats and filters when it is created. Pages are read in archive order, so a
     * single session is reused and advanced instead of opening a new handle for every entry.
     *
     * Only accessed while holding this reader as a monitor, together with [close].
     */
    private var session: ArchiveInputStream? = null

    /**
     * Whether [close] has already released the mapping.
     *
     * Without this, closing the reader and asking for an entry afterwards would build a new session
     * straight on top of the address that [close] just unmapped, which libarchive would then read
     * as freed memory. It is also what makes [close] idempotent, since callers can reach it twice
     * (the loader recycles the chapter, and the reader is also a [Closeable] in its own right).
     */
    @Volatile
    private var closed = false
    // KMK <--

    // SY -->
    var encrypted: Boolean = false
        private set
    var wrongPassword: Boolean? = null
        private set
    val archiveHashCode = pfd.hashCode()

    init {
        checkEncryptionStatus()
    }
    // SY <--

    // KMK -->
    init {
        // A ZIP keeps an index of all of its entries, so pages can be read at random instead of
        // walking the archive with libarchive.
        if (!encrypted) {
            zipDirectory = runCatching {
                ZipDirectory.parse(FileDescriptorSource(pfd.fileDescriptor, size))
            }.getOrNull()
        }
    }
    // KMK <--

    inline fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = ArchiveInputStream(
        address,
        size,
        // SY -->
        encrypted,
        // SY <--
    ).use { block(generateSequence { it.getNextEntry() }) }

    // KMK -->
    /**
     * Returns the contents of [entryName] as an independent stream, or null if the archive has no
     * such entry.
     *
     * The entry is copied out of the session while it is positioned on it, so the returned stream
     * keeps working after the session moves on to the next entry. Reading entries in archive order
     * (which is what the reader does for pages) only advances the session, so the per-page scan over
     * the whole archive is reduced to amortized constant time. Jumping backwards restarts the session.
     *
     * The entry is read from the ZIP central directory when one is available, which is what keeps
     * reading a chapter linear, and from the libarchive session otherwise.
     *
     * @throws IllegalStateException if the reader has already been closed. Callers hold this via a
     * long lived lambda (page streams), so asking for an entry after [close] is a real possibility;
     * failing loudly is the only way to avoid handing libarchive an unmapped address.
     */
    @Synchronized
    fun getInputStream(entryName: String): InputStream? {
        check(!closed) { "ArchiveReader is closed" }

        // KMK -->
        // Read the entry straight from its offset. With libarchive alone this costs one header walk
        // per page, and that walk restarts whenever the pages are not stored in archive order,
        // which is what made large CBZ files slow to open.
        zipDirectory?.open(entryName)?.let { return it }
        // KMK <--

        return try {
            positionSessionOn(entryName)?.readBytes()?.inputStream()
        } catch (e: ArchiveException) {
            resetSession()
            throw e
        }
    }

    /** Moves the session forward until it is positioned on [entryName]. */
    private fun positionSessionOn(entryName: String): ArchiveInputStream? {
        if (session == null || entryName in consumedEntries) {
            resetSession()
            session = ArchiveInputStream(address, size, encrypted)
        }

        val stream = session!!
        while (true) {
            val entry = stream.getNextEntry() ?: return null
            consumedEntries += entry.name
            if (entry.name == entryName) return stream
        }
    }

    private fun resetSession() {
        session?.close()
        session = null
        consumedEntries.clear()
    }
    // KMK <--

    // SY -->
    private fun checkEncryptionStatus() {
        val archive = ArchiveInputStream(address, size, false)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.isEncrypted) {
                    encrypted = true
                    isPasswordIncorrect(entry.name)
                    break
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
    }

    private fun isPasswordIncorrect(entryName: String) {
        try {
            getInputStream(entryName).use { stream ->
                stream!!.read()
            }
        } catch (e: ArchiveException) {
            if (e.message == "Incorrect passphrase") {
                wrongPassword = true
                return
            }
            throw e
        }
        wrongPassword = false
    }
    // SY <--

    override fun close() {
        // KMK -->
        synchronized(this) {
            if (closed) return
            closed = true

            resetSession()
            zipDirectory = null
            Os.munmap(address, size)
            pfd.close()
        }
        // KMK <--
    }
}
