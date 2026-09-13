package mihon.core.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile

internal fun UniFile.openFileDescriptor(context: Context, mode: String): ParcelFileDescriptor =
    context.contentResolver.openFileDescriptor(uri, mode) ?: error("Failed to open file descriptor: ${filePath ?: uri}")

// KMK -->
// The reader keeps the descriptor open: it reads ZIP entries directly from it (see ZipDirectory)
// and closes it in close(), together with the mapping.
fun UniFile.archiveReader(context: Context) = ArchiveReader(openFileDescriptor(context, "r"))
// KMK <--

fun UniFile.epubReader(context: Context) = EpubReader(archiveReader(context))
