package eu.kanade.tachiyomi.data.updater

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import exh.source.ExhPreferences
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.service.AppUpdatePolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AppUpdateChecker(
    // KMK -->
    private val peekIntoPreview: Boolean = false,
    // KMK <--
) {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()

    // KMK -->
    private val exhPreferences by lazy { Injekt.get<ExhPreferences>() }
    // KMK <--

    suspend fun checkForUpdate(
        context: Context,
        forceCheck: Boolean = false,
        // KMK -->
        autoUpdate: Boolean = AppUpdatePolicy.DISABLE_AUTO_DOWNLOAD !in exhPreferences.appShouldAutoUpdate().get(),
        // KMK <--
    ): GetApplicationRelease.Result {
        return withIOContext {
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFoss = isFossBuildType,
                    isPreview = isPreviewBuildType || peekIntoPreview,
                    commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                    versionName = BuildConfig.VERSION_NAME,
                    repository = getGithubRepo(peekIntoPreview),
                    forceCheck = forceCheck,
                ),
            )

            // KMK -->
            if (!peekIntoPreview) {
                // KMK <--
                when (result) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        // KMK -->
                        AppUpdateNotifier.releasePageUrl = result.release.releaseLink
                        // KMK <--
                        AppUpdateNotifier(context).promptUpdate(result.release)
                    }

                    else -> {}
                }

                // KMK -->
                if (autoUpdate && result is GetApplicationRelease.Result.NewUpdate) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        AppUpdateDownloadJob.start(
                            context = context,
                            url = result.release.downloadLink,
                            title = result.release.version,
                            scheduled = true,
                        )
                    }
                }
                // KMK <--
            }

            result
        }
    }

    // KMK -->
    suspend fun getReleaseNotes(): GetApplicationRelease.Result {
        return withIOContext {
            getApplicationRelease.awaitReleaseNotes(
                GetApplicationRelease.Arguments(
                    isFoss = isFossBuildType,
                    isPreview = isPreviewBuildType || peekIntoPreview,
                    commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                    versionName = BuildConfig.VERSION_NAME,
                    repository = getGithubRepo(peekIntoPreview),
                ),
            )
        }
    }
    // KMK <--
}

// KMK -->
/**
 * 自维护 fork：CI 用同一把密钥构建并发布 `kmk-<上游 tag>` 的 release，
 * 应用内更新直接从这个仓库下载，而不是去上游官网。
 */
const val KMK_REPO = "jldxnb/komikku"
// KMK <--

val GITHUB_REPO: String by lazy { getGithubRepo() }

fun getGithubRepo(peekIntoPreview: Boolean = false): String =
    if (isPreviewBuildType || peekIntoPreview) {
        "komikku-app/komikku-preview"
    } else {
        // KMK -->
        KMK_REPO
        // KMK <--
    }

val RELEASE_TAG: String by lazy { getReleaseTag() }

fun getReleaseTag(peekIntoPreview: Boolean = false): String =
    if (isPreviewBuildType || peekIntoPreview) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        // KMK -->
        // 我们自己发布的 tag 形如 kmk-v1.14.1，见 .github/workflows/kmk-release.yml
        "kmk-v${BuildConfig.VERSION_NAME}"
        // KMK <--
    }

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
