package eu.kanade.tachiyomi.data.coil

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

// KMK -->
/**
 * 把封面落到"漫画的下载目录"里，并让程序优先读这份本地文件。
 *
 * 背景：以前封面只存在于应用缓存（`CoverCache`）里，缓存一被清、源又失效，封面就永久丢失。
 * 现在封面会以 `cover.jpg` 的形式写进 `<存储位置>/downloads/<源名>/<漫画名>/`：
 *  - 读：本地有就直接用（不联网、不更新）；
 *  - 写：只有"网络真的拉到图"时才覆盖，失败一律保留原文件；
 *  - 下拉刷新时主动拉一次，成功才覆盖（见 MangaScreenModel / LocalCoverBackfillJob）。
 *
 * 是否"有本地封面"来自下载索引（[DownloadCache]），所以读路径是内存查表，没有额外文件 IO。
 */
internal object LocalCoverStore {
    private const val COVER_FILE_NAME = "cover.jpg"
    private const val COVER_TEMP_SUFFIX = ".tmp"

    private val downloadCache: DownloadCache by lazy { Injekt.get() }
    private val context: Context by lazy { Injekt.get() }
    private val networkHelper: NetworkHelper by lazy { Injekt.get() }
    private val mangaRepository: MangaRepository by lazy { Injekt.get() }

    /** 会话内缓存 mangaId → ogTitle，避免每一张封面都查一次数据库。 */
    private val titleByMangaId = ConcurrentHashMap<Long, String>()
    private val unknownTitle = ConcurrentHashMap.newKeySet<Long>()

    /** 按漫画 id 取本地封面 URI（封面加载器手里只有 id 与 sourceId）。 */
    suspend fun localCoverUriFor(mangaId: Long, sourceId: Long): String? {
        val title = titleOf(mangaId) ?: return null
        return downloadCache.getLocalCoverUri(sourceId, title)
    }

    /** 打开失败时清掉记录（按 id）。 */
    suspend fun forgetCover(mangaId: Long, sourceId: Long) {
        val title = titleOf(mangaId) ?: return
        downloadCache.setLocalCoverUri(sourceId, title, null)
    }

    /** 网络拉取成功后，把已缓存到 `CoverCache` 的封面复制进漫画目录（只覆盖，不新建目录）。 */
    suspend fun saveFromCachedCover(mangaId: Long, sourceId: Long, cacheFile: File): Boolean {
        val title = titleOf(mangaId) ?: return false
        return try {
            save(sourceId, title, cacheFile.readBytes())
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun titleOf(mangaId: Long): String? {
        titleByMangaId[mangaId]?.let { return it }
        if (mangaId in unknownTitle) return null
        val title = try {
            mangaRepository.getMangaById(mangaId).ogTitle
        } catch (e: Exception) {
            null
        }
        if (title.isNullOrBlank()) {
            unknownTitle += mangaId
            return null
        }
        titleByMangaId[mangaId] = title
        return title
    }

    /** 文件名是否被视为封面（`cover.jpg` / `cover.png` / `cover.cbi` …）。 */
    fun isCoverFileName(name: String?): Boolean =
        name != null &&
            name.contains('.') &&
            name.substringBeforeLast('.').equals("cover", ignoreCase = true)

    /** 该漫画当前的本地封面 URI（查索引，无 IO）。 */
    fun localCoverUri(manga: Manga): String? =
        downloadCache.getLocalCoverUri(manga.source, manga.ogTitle)

    /** 打开本地封面文件；文件已不在时清掉索引记录并返回 null。 */
    fun open(manga: Manga): InputStream? {
        val uri = localCoverUri(manga) ?: return null
        val file = try {
            UniFile.fromUri(context, uri.toUri())
        } catch (e: Exception) {
            null
        }
        val stream = try {
            file?.openInputStream()
        } catch (e: Exception) {
            null
        }
        if (stream == null) {
            forget(manga)
        }
        return stream
    }

    /** 清掉"有本地封面"的记录（文件被删/打开失败时用），避免每次重试。 */
    fun forget(manga: Manga) {
        if (localCoverUri(manga) != null) {
            downloadCache.setLocalCoverUri(manga.source, manga.ogTitle, null)
        }
    }

    /**
     * 把封面写进该漫画的下载目录（只在"已有下载目录"时写，绝不凭空造目录）。
     * 先写临时文件、再改名，避免留下半个文件。成功返回 true。
     */
    suspend fun save(manga: Manga, bytes: ByteArray): Boolean =
        save(manga.source, manga.ogTitle, bytes)

    suspend fun save(sourceId: Long, mangaTitle: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val dir = downloadCache.getMangaDir(sourceId, mangaTitle) ?: return@withContext false
        try {
            val tmp = dir.createFile("$COVER_FILE_NAME$COVER_TEMP_SUFFIX") ?: return@withContext false
            tmp.openOutputStream().use { output -> output.write(bytes) }
            dir.findFile(COVER_FILE_NAME)?.delete()
            if (!tmp.renameTo(COVER_FILE_NAME)) return@withContext false
            val saved = dir.findFile(COVER_FILE_NAME) ?: return@withContext false
            downloadCache.setLocalCoverUri(sourceId, mangaTitle, saved.uri.toString())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write local cover for $mangaTitle" }
            false
        }
    }

    /**
     * 从网络拉一次封面，**成功才覆盖**本地文件；失败/超时一律保持原样（不删、不写半个文件）。
     * 只对"已经有下载目录"的漫画执行。
     */
    suspend fun refreshFromNetwork(manga: Manga, source: Source?): Boolean = withContext(Dispatchers.IO) {
        val url = manga.ogThumbnailUrl ?: return@withContext false
        if (downloadCache.getMangaDir(manga.source, manga.ogTitle) == null) return@withContext false

        try {
            val httpSource = source as? HttpSource
            val client = httpSource?.client ?: networkHelper.client
            val request = Request.Builder()
                .url(url)
                .apply { httpSource?.let { headers(it.headers) } }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                save(manga, response.body.bytes())
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to refresh local cover for ${manga.title}" }
            false
        }
    }
}
// KMK <--
