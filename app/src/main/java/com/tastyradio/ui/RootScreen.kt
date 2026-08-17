package com.tastyradio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tastyradio.data.M3uExport
import com.tastyradio.data.Settings
import com.tastyradio.data.StationRepository
import com.tastyradio.playback.Mixer
import com.tastyradio.record.Recorder
import com.tastyradio.search.SearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val glyph: String) {
    /** Stations draws its own radio; the glyph here is unused for it. */
    Stations("Stations", ""),
    Search("Search", "⌕"),
    Settings("Settings", "⚙"),
}

/**
 * Three tabs: Stations, Search, Settings. This replaces Transistor's single screen with its
 * `+ Add new station` / `⚙ Settings` pills at the end of the list — both are tabs now, and the
 * station list gets simpler for it.
 *
 * ## The vertical budget
 * The nav bar, the collapsed mixer pill and the expanded mixer sheet all want the bottom of the
 * screen. Per the design decision: the collapsed pill sits *above* the nav bar, and expanding the
 * mixer takes the nav bar's space too, giving it back on collapse. That's why `mixerExpanded` is
 * hoisted to here rather than living inside [MixerBar] — the nav bar needs to know.
 *
 * No navigation library: three destinations and no back stack to model don't need a nav graph.
 */
@Composable
fun RootScreen(
    repository: StationRepository,
    mixer: Mixer,
    recorder: Recorder,
    search: SearchRepository,
    settings: Settings,
) {
    val settingsValues by settings.values.collectAsStateWithLifecycle(initialValue = Settings.Values())
    var tab by remember { mutableStateOf(Tab.Stations) }
    var mixerExpanded by remember { mutableStateOf(false) }
    var showAddStation by remember { mutableStateOf(false) }
    val channels by mixer.channels.collectAsStateWithLifecycle()
    val recording by recorder.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notify: (String) -> Unit = { message -> scope.launch { snackbars.showSnackbar(message) } }

    val recordingLauncher = rememberRecordingLauncher(
        title = { channels.joinToString(" + ") { it.station.name }.ifEmpty { "Tasty Radio" } },
        onMessage = notify,
    )

    // The moment a take stops is the moment you want to send it, so offer that straight away.
    LaunchedEffect(recording) {
        val saved = recording as? Recorder.State.Saved ?: return@LaunchedEffect
        val seconds = saved.durationMs / 1000
        val result = snackbars.showSnackbar(
            message = "Saved ${saved.fileName} (%d:%02d)".format(seconds / 60, seconds % 60),
            actionLabel = "Share",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) shareRecording(context, saved)
        recorder.acknowledge()
    }

    LaunchedEffect(recording) {
        val failed = recording as? Recorder.State.Failed ?: return@LaunchedEffect
        snackbars.showSnackbar("Recording failed: ${failed.reason}")
        recorder.acknowledge()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            Column {
                // Derived, never trusted from state alone: stopping the last station while the
                // sheet was open used to leave `mixerExpanded` true with no pill left to collapse
                // it, so the navigation bar vanished with no way back.
                val sheetExpanded = mixerExpanded && channels.isNotEmpty()

                MixerBar(
                    channels = channels,
                    expanded = sheetExpanded,
                    onExpandedChange = { mixerExpanded = it },
                    onFader = mixer::setFader,
                    onMute = mixer::setMuted,
                    onStopChannel = mixer::stop,
                    onStopAll = mixer::stopAll,
                    onRetry = mixer::retry,
                    onTone = mixer::setTone,
                    recording = recording,
                    onToggleRecording = {
                        if (recording is Recorder.State.Recording) {
                            recordingLauncher.stop()
                        } else {
                            recordingLauncher.start()
                        }
                    },
                )
                if (!sheetExpanded) {
                    NavigationBar {
                        Tab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = {
                                    if (entry == Tab.Stations) {
                                        RadioGlyph(
                                            tint = if (tab == entry) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    } else {
                                        Text(entry.glyph, style = MaterialTheme.typography.titleLarge)
                                    }
                                },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.Stations -> StationListScreen(
                repository = repository,
                mixer = mixer,
                channels = channels,
                contentPadding = padding,
                onNotify = notify,
            )

            Tab.Search -> SearchScreen(
                search = search,
                modifier = Modifier.padding(padding),
                onAddByUrl = { showAddStation = true },
                onSync = { scope.launch { search.sync() } },
                onAudition = { station ->
                    // Quietly, under whatever is already playing — that's the only way to tell
                    // whether it belongs there.
                    if (!mixer.play(station, Mixer.AUDITION_FADER)) {
                        notify("The mix is full — stop a station before auditioning another.")
                    }
                },
                onAdd = { hit ->
                    scope.launch {
                        val added = repository.add(
                            name = hit.name,
                            streamUrl = hit.url,
                            imageUrl = hit.favicon.ifBlank { null },
                            sourceUuid = hit.uuid.ifBlank { null },
                            source = hit.source,
                            tags = hit.tags,
                            codec = hit.codec,
                            bitrate = hit.bitrate,
                            country = hit.country,
                            language = hit.language,
                        )
                        notify(
                            if (added == null) "Already in your collection." else "Added ${hit.name}."
                        )
                    }
                },
            )

            Tab.Settings -> SettingsScreen(
                search = search,
                settings = settingsValues,
                modifier = Modifier.padding(padding),
                onLargeBuffer = { enabled ->
                    scope.launch {
                        settings.setLargeBuffer(enabled)
                        notify(
                            if (enabled) {
                                "Large buffer on — steadier, slower to start."
                            } else {
                                "Large buffer off — starts faster, more prone to dropouts."
                            }
                        )
                    }
                },
                onRefresh = { frequency ->
                    scope.launch {
                        settings.setRefresh(frequency)
                        notify(
                            when (frequency) {
                                Settings.RefreshFrequency.Off -> "Automatic refresh off."
                                else -> "Index will refresh ${frequency.label.lowercase()} on Wi-Fi while charging."
                            }
                        )
                    }
                },
                onSync = { scope.launch { search.sync() } },
                onClearIndex = {
                    scope.launch {
                        search.clearIndex()
                        notify("Station index cleared.")
                    }
                },
                onExportM3u = {
                    scope.launch {
                        val stations = repository.stations.first()
                        val uri = M3uExport.export(context, stations)
                        notify(
                            if (uri == null) {
                                "Nothing to export."
                            } else {
                                "Exported ${stations.size} stations to Downloads/Tasty Radio."
                            }
                        )
                    }
                },
            )
        }
    }

    if (showAddStation) {
        AddStationDialog(
            repository = repository,
            onDismiss = { showAddStation = false },
            onResult = notify,
        )
    }
}
