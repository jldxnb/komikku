package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.data.coil.LocalCoverStore
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.hours

// KMK -->
/**
 * 把应用封面缓存里的图补进各个漫画的下载目录（`cover.jpg`）。
 *
 * 只做本地复制：不联网、不新建目录、不覆盖已有封面。
 * 触发方式：应用启动时（带节流）+ 设置 → 高级 → 书库 里的手动入口。
 */
class LocalCoverBackfillJob(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val preferenceStore: PreferenceStore = Injekt.get()
    private val downloadProvider: DownloadProvider = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val lastRun = preferenceStore.getLong(Preference.appStateKey(KEY_LAST_RUN), 0L).get()
        if (!force && System.currentTimeMillis() - lastRun < THROTTLE.inWholeMilliseconds) {
            return Result.success()
        }

        return try {
            var scanned = 0
            var written = 0
            // 每个来源只查一次"下载目录在不在"，省掉大量无谓的磁盘查询
            val sourceHasDownloads = HashMap<Long, Boolean>()

            getLibraryManga.await().forEach { libraryManga ->
                if (isStopped) return Result.failure()
                val manga = libraryManga.manga
                // 本地源漫画的封面由本地源自己管理，这里只管"下载目录"
                if (manga.isLocal()) return@forEach

                val hasDownloads = sourceHasDownloads.getOrPut(manga.source) {
                    try {
                        downloadProvider.findSourceDir(sourceManager.getOrStub(manga.source)) != null
                    } catch (e: Exception) {
                        false
                    }
                }
                if (!hasDownloads) return@forEach

                scanned++
                if (LocalCoverStore.backfillFromCache(manga)) written++
            }

            preferenceStore.getLong(Preference.appStateKey(KEY_LAST_RUN)).set(System.currentTimeMillis())
            logcat(LogPriority.INFO) { "Local cover backfill: scanned=$scanned written=$written" }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Local cover backfill failed" }
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "LocalCoverBackfillJob"
        private const val KEY_FORCE = "force"
        private const val KEY_LAST_RUN = "kmk_local_cover_backfill_last_run"
        private val THROTTLE = 1.hours

        /** 排队一次回填；force = true 时忽略节流（设置里的手动入口用）。 */
        fun startNow(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<LocalCoverBackfillJob>()
                .setInputData(workDataOf(KEY_FORCE to force))
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }
    }
}
// KMK <--
