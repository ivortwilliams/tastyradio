package com.tastyradio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tastyradio.data.StationRepository
import com.tastyradio.playback.Mixer
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val glyph: String) {
    Stations("Stations", "≡"),
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
) {
    var tab by remember { mutableStateOf(Tab.Stations) }
    var mixerExpanded by remember { mutableStateOf(false) }
    var showAddStation by remember { mutableStateOf(false) }
    val channels by mixer.channels.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { message -> scope.launch { snackbars.showSnackbar(message) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            Column {
                MixerBar(
                    channels = channels,
                    expanded = mixerExpanded,
                    onExpandedChange = { mixerExpanded = it },
                    onFader = mixer::setFader,
                    onMute = mixer::setMuted,
                    onStopChannel = mixer::stop,
                    onStopAll = mixer::stopAll,
                    onRetry = mixer::retry,
                )
                if (!mixerExpanded) {
                    NavigationBar {
                        Tab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = {
                                    Text(entry.glyph, style = MaterialTheme.typography.titleLarge)
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
                modifier = Modifier.padding(padding),
                onAddByUrl = { showAddStation = true },
            )

            Tab.Settings -> SettingsScreen(modifier = Modifier.padding(padding))
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
