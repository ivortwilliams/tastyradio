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
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.unit.dp
import com.tastyradio.data.M3uExport
import com.tastyradio.data.MixRepository
import com.tastyradio.data.Settings
import com.tastyradio.data.StationRepository
import com.tastyradio.playback.Mixer
import com.tastyradio.record.Recorder
import com.tastyradio.search.SearchRepository
import com.tastyradio.share.MixLink
import com.tastyradio.update.Updater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Mixes first, and the tab the app opens on: if saved soundscapes are how you listen, they should
 * be under your thumb the moment the app appears.
 *
 * Every tab draws its icon on a fixed-size Canvas. Two of them used to be text characters, whose
 * slot height comes from the font's line metrics rather than the size you asked for — which sat
 * those labels visibly lower than their neighbours.
 */
private enum class Tab(val label: String) {
    Mixes("Mixes"),
    Stations("Stations"),
    Search("Search"),
    Settings("Settings"),
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
    mixRepository: MixRepository,
    mixer: Mixer,
    recorder: Recorder,
    search: SearchRepository,
    settings: Settings,
    updater: Updater,
    /** A mix that arrived as a link, waiting to be offered. See [MixLink]. */
    sharedMix: MixLink.Shared? = null,
    onSharedMixHandled: () -> Unit = {},
) {
    val settingsValues by settings.values.collectAsStateWithLifecycle(initialValue = Settings.Values())
    var tab by remember { mutableStateOf(Tab.Mixes) }
    var mixerExpanded by remember { mutableStateOf(false) }
    var showAddStation by remember { mutableStateOf(false) }
    var showSaveMix by remember { mutableStateOf(false) }
    /**
     * The mix you're working on. Survives adding and removing stations, because adding a station to
     * a loaded mix means you're *editing that mix* — the save dialog should still offer its name
     * rather than making you type it again. Only stopping everything clears it.
     */
    var liveMixName by remember { mutableStateOf<String?>(null) }
    val channels by mixer.channels.collectAsStateWithLifecycle()
    val mixes by mixRepository.mixes.collectAsStateWithLifecycle(initialValue = emptyList())
    val stations by repository.stations.collectAsStateWithLifecycle(initialValue = emptyList())
    val recording by recorder.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notify: (String) -> Unit = { message -> scope.launch { snackbars.showSnackbar(message) } }

    val recordingLauncher = rememberRecordingLauncher(
        title = { channels.joinToString(" + ") { it.station.name }.ifEmpty { "Tasty Radio" } },
        onMessage = notify,
    )

    // Stopping everything is the one thing that ends the working mix. Adding and removing stations
    // is editing it; an empty mixer is not a mix any more.
    LaunchedEffect(channels.isEmpty()) {
        if (channels.isEmpty()) liveMixName = null
    }

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
                // sheet was open used to leave `mixerExpanded` true with nothing left to collapse it.
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
                    onSaveMix = { showSaveMix = true },
                    onShareMix = {
                        MixLink.share(
                            context,
                            liveMixName ?: channels.joinToString(" + ") { it.station.name },
                            channels.map {
                                MixLink.Channel(
                                    station = it.station,
                                    fader = it.fader,
                                    muted = it.muted,
                                    tone = it.tone,
                                )
                            },
                        )
                    },
                    recording = recording,
                    onToggleRecording = {
                        if (recording is Recorder.State.Recording) {
                            recordingLauncher.stop()
                        } else {
                            recordingLauncher.start()
                        }
                    },
                )
                // The navigation bar stays put even with the mixer open. Hiding it left you
                // stranded on whichever tab you happened to be on while adjusting levels.
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = {
                                tab = entry
                                // Changing tab means you're done fiddling: give the screen back.
                                mixerExpanded = false
                            },
                            icon = {
                                val tint = if (tab == entry) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                when (entry) {
                                    Tab.Mixes -> FadersGlyph(tint = tint)
                                    Tab.Stations -> RadioGlyph(tint = tint)
                                    Tab.Search -> SearchGlyph(tint = tint)
                                    Tab.Settings -> SettingsGlyph(tint = tint)
                                }
                            },
                            label = { Text(entry.label) },
                        )
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

            Tab.Mixes -> MixesScreen(
                mixes = mixes,
                stations = stations,
                liveMixName = liveMixName,
                contentPadding = padding,
                onPlay = { entry ->
                    val byId = stations.associateBy { it.id }
                    val presets = entry.channels.mapNotNull { channel ->
                        byId[channel.stationId]?.let { station ->
                            Mixer.Preset(
                                station = station,
                                fader = channel.fader,
                                muted = channel.muted,
                                tone = Mixer.Tone(
                                    low = channel.toneLow,
                                    mid = channel.toneMid,
                                    high = channel.toneHigh,
                                    reverb = channel.reverb,
                                    delay = channel.delay,
                                    delayMs = channel.delayMs,
                                ),
                            )
                        }
                    }
                    if (presets.isEmpty()) {
                        notify("None of that mix's stations are in your collection any more.")
                    } else {
                        mixer.load(presets)
                        liveMixName = entry.mix.name
                        notify("Playing ${entry.mix.name}.")
                    }
                },
                onShare = { entry ->
                    val byId = stations.associateBy { it.id }
                    val channels = entry.channels.mapNotNull { channel ->
                        byId[channel.stationId]?.let { station ->
                            MixLink.Channel(
                                station = station,
                                fader = channel.fader,
                                muted = channel.muted,
                                tone = Mixer.Tone(
                                    low = channel.toneLow,
                                    mid = channel.toneMid,
                                    high = channel.toneHigh,
                                    reverb = channel.reverb,
                                    delay = channel.delay,
                                    delayMs = channel.delayMs,
                                ),
                            )
                        }
                    }
                    if (channels.isEmpty()) {
                        notify("None of that mix's stations are in your collection any more.")
                    } else {
                        MixLink.share(context, entry.mix.name, channels)
                    }
                },
                onRename = { mix, name ->
                    scope.launch {
                        mixRepository.rename(mix, name)
                        if (liveMixName == mix.name) liveMixName = name.trim()
                    }
                },
                onDelete = { mix ->
                    scope.launch {
                        mixRepository.delete(mix)
                        if (liveMixName == mix.name) liveMixName = null
                        notify("Deleted ${mix.name}.")
                    }
                },
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
                updater = updater,
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

    if (showSaveMix) {
        // Prefilled with the station names, because that's what the mix is until you name it
        // something better — and an unnamed mix you have to invent a title for doesn't get saved.
        var name by remember { mutableStateOf(liveMixName ?: channels.joinToString(" + ") { it.station.name }) }
        AlertDialog(
            onDismissRequest = { showSaveMix = false },
            title = { Text("Save this mix") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Saves the stations, their levels, mutes and tone. Using a name you " +
                            "already have replaces that mix.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val result = mixRepository.save(name, channels)
                            liveMixName = name.trim()
                            showSaveMix = false
                            // Saving can collect a station auditioned from search, and a station
                            // appearing in your list without being told would be a small mystery.
                            val counted = "${result.stations} station" +
                                if (result.stations == 1) "" else "s"
                            val collected = when (result.added) {
                                0 -> ""
                                1 -> " · 1 added to your stations"
                                else -> " · ${result.added} added to your stations"
                            }
                            notify(
                                when {
                                    result.stations == 0 ->
                                        "Nothing to save — the mixer is empty."
                                    // Says which of the two things happened, so replacing an
                                    // existing mix never looks like it made a second one.
                                    result.replaced ->
                                        "Updated “${name.trim()}” ($counted)$collected"
                                    else ->
                                        "Saved “${name.trim()}” ($counted)$collected"
                                }
                            )
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSaveMix = false }) { Text("Cancel") } },
        )
    }

    if (showAddStation) {
        AddStationDialog(
            repository = repository,
            onDismiss = { showAddStation = false },
            onResult = notify,
        )
    }

    sharedMix?.let { shared ->
        SharedMixDialog(
            shared = shared,
            knownUrls = stations.map { it.streamUrl }.toSet(),
            onDismiss = onSharedMixHandled,
            onOpen = { keep ->
                // A station you already have is used as the row you already have, so a shared mix
                // never leaves a duplicate behind and your own artwork and edits survive.
                val byUrl = stations.associateBy { it.streamUrl }
                mixer.load(
                    shared.channels.map { channel ->
                        Mixer.Preset(
                            station = byUrl[channel.station.streamUrl] ?: channel.station,
                            fader = channel.fader,
                            muted = channel.muted,
                            tone = channel.tone,
                        )
                    }
                )
                liveMixName = shared.name
                onSharedMixHandled()
                if (keep) {
                    scope.launch {
                        // Never on top of a mix you already have: everyone starts with the same
                        // three shipped soundscapes, so a friend's “Ritual Gregorian” arriving
                        // must not quietly replace yours.
                        val name = mixRepository.availableName(shared.name)
                        // The same save the Save button uses, which is what collects the stations
                        // that aren't yours yet — matched by stream URL, so nothing duplicates.
                        val result = mixRepository.save(name, mixer.channels.value)
                        if (result.stations > 0) liveMixName = name
                        val renamed = if (name != shared.name) " as “$name”" else ""
                        notify(
                            when {
                                result.stations == 0 -> "Could not keep that mix."
                                result.added > 0 ->
                                    "Kept “${shared.name}”$renamed · ${result.added} added to your stations"
                                else -> "Kept “${shared.name}”$renamed"
                            }
                        )
                    }
                } else {
                    notify("Playing ${shared.name}.")
                }
            },
        )
    }

    // Last, so it sits above everything else: an update offer is the one thing worth interrupting
    // for, and it only ever appears when there is genuinely a newer build.
    UpdatePrompt(updater)
}
