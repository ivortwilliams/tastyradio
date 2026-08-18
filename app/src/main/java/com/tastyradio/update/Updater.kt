package com.tastyradio.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the app up to date on phones that never went near an app store.
 *
 * This app is handed to friends as an APK, which normally means every fix is a message saying
 * "download this again" — so the app checks for itself. A tiny `version.json` published beside each
 * release says what the newest build is; if that is higher than the installed one, the app offers
 * to fetch the APK and hands it to the system installer. The update lands *over* the existing app,
 * so stations, mixes and settings survive — which is only true because every release is signed with
 * the same key.
 *
 * Deliberately not silent: Android has no way for an ordinary app to install itself without the
 * user agreeing, and pretending otherwise would just fail quietly on their phone. Two taps is the
 * floor, and this makes them the only two.
 */
class Updater(private val context: Context) {

    /** What a release says about itself. Small on purpose: this file is fetched on every launch. */
    data class Release(
        val versionCode: Long,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val checkedAt: Long) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        /** Downloaded and handed to the installer; the system dialog is the next thing you see. */
        data class Ready(val release: Release) : State
        /**
         * [loud] is true only when the user asked for this check. A phone with no signal fails the
         * automatic check on every launch, and that must not become a dialog.
         */
        data class Failed(val reason: String, val loud: Boolean = false) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The build running right now, from the package manager rather than a generated constant. */
    val installedVersionCode: Long by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
    }

    val installedVersionName: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    fun check(force: Boolean = false) {
        if (_state.value is State.Checking) return
        if (!force && _state.value !is State.Idle) return
        scope.launch {
            _state.value = State.Checking
            _state.value = runCatching { fetchManifest() }.fold(
                onSuccess = { release ->
                    when {
                        release == null -> State.Failed("No release published yet")
                        release.versionCode > installedVersionCode -> State.Available(release)
                        else -> State.UpToDate(System.currentTimeMillis())
                    }
                },
                onFailure = { error ->
                    State.Failed(error.message ?: "Could not reach the update server", loud = force)
                },
            )
        }
    }

    fun download(release: Release) {
        scope.launch {
            _state.value = State.Downloading(release, 0)
            runCatching { fetchApk(release) }.fold(
                onSuccess = { file ->
                    _state.value = State.Ready(release)
                    withContext(Dispatchers.Main) { install(file) }
                },
                onFailure = { error ->
                    // The user pressed Update, so this one is always worth saying out loud.
                    _state.value = State.Failed(error.message ?: "Download failed", loud = true)
                },
            )
        }
    }

    /** Back to quiet. The next launch asks again, which is the right amount of nagging. */
    fun dismiss() {
        _state.value = State.Idle
    }

    // ------------------------------------------------------------------ the network side

    private fun fetchManifest(): Release? {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
            // GitHub answers the /latest/download/ URL with a redirect to its CDN.
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode == 404) return null
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Update server said ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return Release(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                notes = json.optString("notes"),
                apkUrl = json.getString("apkUrl"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchApk(release: Release): File {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        // One file, overwritten: a half-finished download from last time is worth nothing.
        val destination = File(folder, "TastyRadio.apk")

        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed (${connection.responseCode})")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            _state.value = State.Downloading(release, (copied * 100 / total).toInt())
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return destination
    }

    // ------------------------------------------------------------------ the install side

    /**
     * True once the user has allowed this app to install apps. Android asks for that per-app, so
     * the first update needs a trip to a settings screen — after that, never again.
     */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the one settings screen that can grant it, already scoped to this app. */
    fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { _state.value = State.Failed("No installer on this phone", loud = true) }
    }

    companion object {
        /**
         * Published beside the APK on every release. The `latest` path is what makes this work
         * without a server of our own: GitHub resolves it to whichever release is newest, so the
         * URL baked into an old build still finds new ones.
         */
        const val MANIFEST_URL =
            "https://github.com/ivortwilliams/tastyradio/releases/latest/download/version.json"

        private const val USER_AGENT = "TastyRadio (+https://github.com/ivortwilliams/tastyradio)"
    }
}
