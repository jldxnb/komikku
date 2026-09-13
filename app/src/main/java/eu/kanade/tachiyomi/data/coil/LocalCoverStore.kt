package eu.kanade.tachiyomi.data.coil

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
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
    private const val COVER_BACKUP_SUFFIX = ".bak"

    private val downloadCache: DownloadCache by lazy { Injekt.get() }
    private val context: Context by lazy { Injekt.get() }
    private val networkHelper: NetworkHelper by lazy { Injekt.get() }
    private val mangaRepository: MangaRepository by lazy { Injekt.get() }
    private val coverCache: CoverCache by lazy { Injekt.get() }
    private val downloadProvider: DownloadProvider by lazy { Injekt.get() }
    private val sourceManager: SourceManager by lazy { Injekt.get() }

    /** "显示即回填"用的后台作用域：只在后台复制文件，绝不阻塞封面显示。 */
    private val backfillScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** 本次运行里已经处理过的漫画（失败会放开，允许下次再试）。 */
    private val backfillAttempted = ConcurrentHashMap.newKeySet<Long>()

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

    suspend fun save(sourceId: Long, mangaTitle: String, bytes: ByteArray): Boolean {
        val dir = downloadCache.getMangaDir(sourceId, mangaTitle) ?: return false
        return saveToDirectory(dir, sourceId, mangaTitle, bytes)
    }

    /**
     * 把封面写进指定目录（目录可能来自磁盘直查，不在下载索引里）。
     * 临时文件名带时间戳，避免两处同时写入时互相踩踏；写完再改名成 cover.jpg。
     */
    suspend fun saveToDirectory(
        dir: UniFile,
        sourceId: Long,
        mangaTitle: String,
        bytes: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext false

        val stamp = System.nanoTime()
        val tmp = try {
            dir.createFile("$COVER_FILE_NAME.$stamp$COVER_TEMP_SUFFIX") ?: return@withContext false
        } catch (e: Exception) {
            return@withContext false
        }

        var replaced = false
        try {
            tmp.openOutputStream().use { output -> output.write(bytes) }

            // 先把旧文件挪成备份；替换成功再删备份，替换失败就还原 —— 绝不静默丢掉已有封面
            val backupName = "$COVER_FILE_NAME.$stamp$COVER_BACKUP_SUFFIX"
            val backedUp = dir.findFile(COVER_FILE_NAME)?.renameTo(backupName) == true
            if (!tmp.renameTo(COVER_FILE_NAME)) {
                if (backedUp) dir.findFile(backupName)?.renameTo(COVER_FILE_NAME)
                return@withContext false
            }
            replaced = true
            if (backedUp) dir.findFile(backupName)?.delete()

            val saved = dir.findFile(COVER_FILE_NAME) ?: return@withContext false
            downloadCache.setLocalCoverUri(sourceId, mangaTitle, saved.uri.toString())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write local cover for $mangaTitle" }
            false
        } finally {
            // 只有没替换成功时，残留的临时文件才是垃圾
            if (!replaced) tmp.delete()
        }
    }

    /**
     * 显示封面时的"顺手回填"：把本次真正用于显示的缓存文件复制进下载目录。
     * - 只做本地复制，绝不联网；
     * - 同一本漫画每次运行只做一次（失败会放开重试）；
     * - 没有下载目录时什么都不做（不新建目录）。
     */
    fun backfillLater(mangaId: Long, sourceId: Long, cacheFile: File?) {
        if (cacheFile == null || !backfillAttempted.add(mangaId)) return
        backfillScope.launch {
            val done = try {
                val title = titleOf(mangaId)
                if (title == null) {
                    false
                } else {
                    val dir = downloadCache.getMangaDir(sourceId, title)
                    if (dir == null) {
                        // 没有下载目录：不标记成"已处理"，等目录出现后再补
                        false
                    } else if (downloadCache.getLocalCoverUri(sourceId, title) != null) {
                        true
                    } else if (!cacheFile.exists()) {
                        false
                    } else {
                        saveToDirectory(dir, sourceId, title, cacheFile.readBytes())
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to backfill local cover for manga $mangaId" }
                false
            }
            if (!done) backfillAttempted.remove(mangaId)
        }
    }

    /**
     * 批量回填（后台任务用）：有下载目录、且还没有本地封面时，从应用封面缓存复制一份。
     * 只用本地文件，不联网、不新建目录。返回 true 表示这次真的写入了（或发现了已存在的文件）。
     */
    suspend fun backfillFromCache(manga: Manga): Boolean {
        if (downloadCache.getLocalCoverUri(manga.source, manga.ogTitle) != null) return false
        val dir = findMangaDir(manga) ?: return false

        val existing = dir.findFile(COVER_FILE_NAME)
        if (existing != null) {
            // 文件已经在磁盘上，只是索引还没扫描到 → 顺手把索引补上
            downloadCache.setLocalCoverUri(manga.source, manga.ogTitle, existing.uri.toString())
            return true
        }

        val cacheFile = preferredCoverCacheFile(manga) ?: return false
        return try {
            saveToDirectory(dir, manga.source, manga.ogTitle, cacheFile.readBytes())
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to backfill local cover for ${manga.title}" }
            false
        }
    }

    /** 优先用"用户实际看到的那张"：自定义封面 → 源站封面缓存。 */
    private fun preferredCoverCacheFile(manga: Manga): File? {
        val customCover = coverCache.getCustomCoverFile(manga.id)
        if (customCover.exists()) return customCover
        return coverCache.getCoverFile(manga.thumbnailUrl)?.takeIf { it.exists() }
    }

    /** 找漫画的下载目录：先查下载索引（快），没有再按磁盘直查（索引里掉号的旧目录也能补上）。 */
    private fun findMangaDir(manga: Manga): UniFile? {
        downloadCache.getMangaDir(manga.source, manga.ogTitle)?.let { return it }
        return try {
            val source = sourceManager.getOrStub(manga.source)
            if (source.isLocal()) null else downloadProvider.findMangaDir(manga.ogTitle, source)
        } catch (e: Exception) {
            null
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
