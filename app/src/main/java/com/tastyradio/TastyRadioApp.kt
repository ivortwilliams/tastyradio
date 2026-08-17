package com.tastyradio

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.tastyradio.data.MixRepository
import com.tastyradio.data.Settings
import com.tastyradio.data.StationRepository
import com.tastyradio.data.TastyDb
import com.tastyradio.search.SyncWorker
import com.tastyradio.playback.Mixer
import com.tastyradio.record.Recorder
import com.tastyradio.search.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-scoped singletons. No dependency-injection framework: this is a radio player, and
 * three objects do not need a container.
 *
 * The [Mixer] lives here rather than in the playback service because both the UI and the service
 * need the same instance.
 */
class TastyRadioApp : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { TastyDb.build(this) }
    val repository by lazy { StationRepository(database.stations()) }
    val mixRepository by lazy { MixRepository(database.mixes()) }
    val mixer by lazy { Mixer(this) }
    val recorder by lazy { Recorder(this) }
    val search by lazy { SearchRepository(this) }
    val settings by lazy { Settings(this) }

    override fun onCreate() {
        super.onCreate()
        // Prefer IPv4 when a host publishes both. Several radio-browser mirrors are dual-stack, and
        // an environment without an IPv6 route (the emulator, plenty of real networks) otherwise
        // fails on the AAAA address.
        System.setProperty("java.net.preferIPv6Addresses", "false")
        appScope.launch { repository.seedIfEmpty() }
        // Never auto-download the corpus: it's tens of megabytes and the user might be on mobile
        // data. This only restores what's already on disk so the UI can say so honestly.
        search.restoreState(appScope)

        // Fill in what older saved stations don't know about themselves. Harmless when the index
        // isn't downloaded — it simply finds nothing and runs again next launch.
        appScope.launch {
            repository.backfillFromIndex { url ->
                search.lookup(url)?.let { hit ->
                    StationRepository.DirectoryFacts(
                        tags = hit.tags,
                        codec = hit.codec,
                        bitrate = hit.bitrate,
                        country = hit.country,
                        language = hit.language,
                        favicon = hit.favicon,
                    )
                }
            }
        }

        // Settings that the Mixer reads directly, kept in sync for the life of the process.
        appScope.launch {
            settings.values.collect { values ->
                mixer.largeBuffer = values.largeBuffer
                SyncWorker.schedule(this@TastyRadioApp, values.refresh)
            }
        }
    }

    /**
     * Station artwork is fetched over the network and cached by Coil. Declared explicitly rather
     * than left to service discovery, so it's obvious where the fetcher comes from — and plenty of
     * favicon URLs are cleartext `http://`, which the network security config already permits.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
