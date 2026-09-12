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
     */
    @Synchronized
    fun getInputStream(entryName: String): InputStream? {
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
            resetSession()
            Os.munmap(address, size)
        }
        // KMK <--
    }
}
