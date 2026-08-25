package com.mdblisthub.tv.update

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

data class ReleaseInfo(
    val tag: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
    val notes: String? = null,
)

sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Downloading(
        val release: ReleaseInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateUiState
    data class AwaitingInstallPermission(val release: ReleaseInfo) : UpdateUiState
    data class Failed(val release: ReleaseInfo?, val detail: String?) : UpdateUiState
}

/**
 * Checks the repository's latest stable GitHub release and installs its APK.
 *
 * GitHub's `releases/latest` endpoint deliberately excludes drafts and
 * prereleases. The installed APK is also compared with every published asset
 * by SHA-256. That makes the check reliable even when a historical release tag
 * and the APK's Android versionName were not kept in sync.
 */
class AppUpdateManager(
    private val activity: Activity,
    private val repository: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private val updateDirectory = File(activity.cacheDir, "updates")
    private val preferences = activity.getSharedPreferences(UPDATE_PREFERENCES, Activity.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var pendingFile: File? = null
    private var pendingRelease: ReleaseInfo? = null
    private var waitingForInstallPermission = false

    fun checkForUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - preferences.getLong(LAST_CHECK_AT, 0L) < CHECK_INTERVAL_MS) return

        scope.launch {
            val release = runCatching { withContext(Dispatchers.IO) { fetchLatestRelease() } }
                .getOrNull()
                ?: return@launch // Startup checks stay quiet when the device is offline.
            preferences.edit { putLong(LAST_CHECK_AT, System.currentTimeMillis()) }

            val installedVersion = runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
            val versionOrder = compareVersions(release.tag, installedVersion)

            // A newer semantic version needs no APK-sized hash to prove it.
            if (versionOrder != null && versionOrder > 0) {
                _state.value = UpdateUiState.Available(release)
                return@launch
            }
            // Never offer the latest published release as a downgrade.
            if (versionOrder != null && versionOrder < 0) return@launch

            val publishedDigests = release.assets.mapNotNull { it.sha256 }
            // An equal or unparseable version without a published digest gives
            // us no evidence that the installed APK differs. Staying quiet is
            // safer than offering the same release on every cold start.
            if (publishedDigests.isEmpty()) return@launch

            val installedDigest = runCatching {
                withContext(Dispatchers.IO) {
                    sha256(File(activity.applicationInfo.sourceDir))
                }
            }.getOrNull()

            val isPublishedAsset = installedDigest != null && publishedDigests.any {
                it.equals(installedDigest, ignoreCase = true)
            }

            // A higher local version is a development build ahead of the last
            // release and must never be downgraded. Equal versions with a
            // different digest are offered because GitHub permits replacing a
            // release asset without changing its tag.
            if (!isPublishedAsset) {
                _state.value = UpdateUiState.Available(release)
            }
        }
    }

    fun downloadAndInstall(release: ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        val asset = selectApkAsset(release.assets, Build.SUPPORTED_ABIS.toList())
        if (asset == null) {
            _state.value = UpdateUiState.Failed(release, "No APK asset in this release")
            return
        }

        downloadJob = scope.launch {
            _state.value = UpdateUiState.Downloading(release, 0L, asset.size)
            runCatching {
                val file = withContext(Dispatchers.IO) { download(release, asset) }
                pendingFile = file
                pendingRelease = release
                requestInstall(file, release)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.value = UpdateUiState.Failed(release, error.message)
            }
        }
    }

    fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        waitingForInstallPermission = false
        pendingFile = null
        pendingRelease = null
        _state.value = UpdateUiState.Hidden
    }

    fun retry() {
        val failure = _state.value as? UpdateUiState.Failed
        val release = failure?.release
        if (release == null) checkForUpdate(force = true) else downloadAndInstall(release)
    }

    fun openInstallPermissionSettings() {
        val release = pendingRelease ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            waitingForInstallPermission = false
            pendingFile?.let { launchPackageInstaller(it, release) }
            return
        }
        waitingForInstallPermission = true
        _state.value = UpdateUiState.AwaitingInstallPermission(release)
        runCatching {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${activity.packageName}".toUri(),
                ),
            )
        }.onFailure { error ->
            waitingForInstallPermission = false
            _state.value = UpdateUiState.Failed(release, error.message)
        }
    }

    /** Called by the host after returning from Android's unknown-apps screen. */
    fun onHostResumed() {
        if (!waitingForInstallPermission) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = false
            val file = pendingFile ?: return
            val release = pendingRelease ?: return
            launchPackageInstaller(file, release)
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun requestInstall(file: File, release: ReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            openInstallPermissionSettings()
        } else {
            launchPackageInstaller(file, release)
        }
    }

    private fun launchPackageInstaller(file: File, release: ReleaseInfo) {
        runCatching {
            val apkUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.update-provider",
                file,
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            pendingFile = null
            pendingRelease = null
            _state.value = UpdateUiState.Hidden
        }.onFailure { error ->
            _state.value = UpdateUiState.Failed(release, error.message)
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val endpoint = "https://api.github.com/repos/$repository/releases/latest"
        val connection = openConnection(endpoint).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Open-Stremio-Updater")
        }
        return try {
            require(connection.responseCode in 200..299) {
                "GitHub returned HTTP ${connection.responseCode}"
            }
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val response = json.decodeFromString<GitHubReleaseResponse>(payload)
            ReleaseInfo(
                tag = response.tagName,
                pageUrl = response.htmlUrl,
                notes = formatReleaseNotes(response.body),
                assets = response.assets
                    .filter { it.name.endsWith(".apk", ignoreCase = true) }
                    .map { asset ->
                        ReleaseAsset(
                            name = asset.name,
                            downloadUrl = asset.downloadUrl,
                            size = asset.size,
                            sha256 = asset.digest
                                ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                                ?.substringAfter(':'),
                        )
                    },
            ).also { require(it.assets.isNotEmpty()) { "Latest release has no APK" } }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(release: ReleaseInfo, asset: ReleaseAsset): File {
        require(asset.downloadUrl.startsWith("https://")) { "Refusing a non-HTTPS download" }
        updateDirectory.mkdirs()
        val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(updateDirectory, "open-stremio-$safeTag.apk")
        val partial = File(updateDirectory, "open-stremio-$safeTag.apk.part")
        val connection = openConnection(asset.downloadUrl).apply {
            setRequestProperty("Accept", APK_MIME_TYPE)
            setRequestProperty("User-Agent", "Open-Stremio-Updater")
        }

        try {
            require(connection.responseCode in 200..299) {
                "Download returned HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: asset.size
            var downloaded = 0L
            var lastPublished = 0L
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastPublished >= PROGRESS_STEP_BYTES || downloaded == total) {
                            lastPublished = downloaded
                            _state.value = UpdateUiState.Downloading(release, downloaded, total)
                        }
                    }
                }
            }
            require(downloaded > 0L) { "Downloaded APK is empty" }
            val expected = requireNotNull(asset.sha256) {
                "Release asset has no published SHA-256 digest"
            }
            require(sha256(partial).equals(expected, ignoreCase = true)) {
                "Downloaded APK failed SHA-256 verification"
            }
            if (destination.exists()) destination.delete()
            require(partial.renameTo(destination)) { "Could not finalize downloaded APK" }
            return destination
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            instanceFollowRedirects = true
        }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val NETWORK_TIMEOUT_MS = 20_000
        const val PROGRESS_STEP_BYTES = 128L * 1024L
        const val UPDATE_PREFERENCES = "app-update-checks"
        const val LAST_CHECK_AT = "last-successful-check-at"
        /**
         * How long a successful check suppresses the next one.
         *
         * A day was chosen against hammering GitHub, but it is the wrong
         * trade for this app: a release *is* the delivery channel here, and
         * the check is one small JSON GET on a screen the viewer opened
         * themselves. At 24 h a release published an hour after the day's
         * first launch stays invisible until the following day, which reads
         * as the updater being broken. Three hours keeps the request rare and
         * puts a release in front of the viewer the same evening it ships.
         */
        const val CHECK_INTERVAL_MS = 3L * 60L * 60L * 1_000L
    }
}

internal fun selectApkAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
    val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    supportedAbis.forEach { abi ->
        apkAssets.firstOrNull { it.name.contains("-$abi-", ignoreCase = true) }?.let { return it }
    }
    return apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
        ?: apkAssets.firstOrNull()
}

/** Returns positive when [remote] is newer, zero when equal, and negative when local is newer. */
internal fun compareVersions(remote: String, local: String): Int? {
    val remoteParts = versionParts(remote) ?: return null
    val localParts = versionParts(local) ?: return null
    val size = maxOf(remoteParts.size, localParts.size)
    repeat(size) { index ->
        val remotePart = remoteParts.getOrElse(index) { 0 }
        val localPart = localParts.getOrElse(index) { 0 }
        if (remotePart != localPart) return remotePart.compareTo(localPart)
    }
    return 0
}

/** Turns the common GitHub Release Markdown into readable Compose text. */
internal fun formatReleaseNotes(markdown: String?): String? = markdown
    ?.replace(Regex("(?s)<!--.*?-->"), "")
    ?.replace(Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]*"""), "")
    ?.replace(Regex("""!\[([^]]*)]\([^)]+\)"""), "$1")
    ?.replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
    ?.replace(Regex("""(?m)^[ \t]*[-*+][ \t]+"""), "• ")
    ?.replace("**", "")
    ?.replace("__", "")
    ?.replace("`", "")
    ?.replace(Regex("\n{3,}"), "\n\n")
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun versionParts(version: String): List<Int>? {
    val parts = Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
    return parts.takeIf { it.isNotEmpty() }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

@Serializable
private data class GitHubReleaseResponse(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val assets: List<GitHubAssetResponse> = emptyList(),
)

@Serializable
private data class GitHubAssetResponse(
    val name: String,
    val size: Long = 0L,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)
