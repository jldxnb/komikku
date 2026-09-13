package me.zhanghai.android.libarchive

import java.nio.ByteBuffer

/**
 * Test double for the real `me.zhanghai.android.libarchive.Archive` binding.
 *
 * The real class runs `System.loadLibrary("archive-jni")` in its static initialiser, and that
 * library only ships for Android ABIs, so the class cannot be loaded in a JVM unit test at all
 * (an `ExceptionInInitializerError` poisons it for the whole JVM, and MockK cannot retransform a
 * class in that state either).
 *
 * Declaring the same fully qualified name here makes the test source set shadow the dependency's
 * copy, because the unit test runtime classpath puts test classes before dependencies. That is what
 * makes [mihon.core.archive.ArchiveInputStream] testable off device.
 *
 * Only the entry points the archive read path calls are declared, and the stubs reproduce the real
 * semantics as implemented by the JNI source (`library/src/main/jni/archive-jni.c`):
 *  * `readData` writes up to `buffer.remaining()` bytes and advances the buffer position; it never
 *    touches the limit
 *  * `readNextHeader` returns 0 once the archive is exhausted
 *
 * The signatures must stay in sync with the real binding: a mismatch shows up as a link error when
 * the unit tests run.
 */
class Archive {

    companion object {
        /**
         * Backing behaviour of [readData]. Set by tests that need a payload; leaving it null makes
         * the archive look empty.
         */
        var readDataHandler: ((ByteBuffer) -> Unit)? = null

        @JvmStatic
        fun readNew(): Long = 1L

        @JvmStatic
        fun setCharset(archive: Long, charset: ByteArray?) = Unit

        @JvmStatic
        fun readSupportFilterAll(archive: Long) = Unit

        @JvmStatic
        fun readSupportFormatAll(archive: Long) = Unit

        @JvmStatic
        fun readOpenMemoryUnsafe(archive: Long, buffer: Long, bufferSize: Long) = Unit

        @JvmStatic
        fun readData(archive: Long, buffer: ByteBuffer) {
            readDataHandler?.invoke(buffer)
        }

        @JvmStatic
        fun readFree(archive: Long) = Unit

        @JvmStatic
        fun readNextHeader(archive: Long): Long = 0L
    }
}
