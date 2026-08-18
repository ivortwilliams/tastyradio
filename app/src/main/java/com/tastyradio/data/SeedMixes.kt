package com.tastyradio.data

import com.tastyradio.playback.ChannelFilters

/**
 * The mixes the app ships with — the reason it exists, already built, so a fresh install is one tap
 * away from two unrelated stations becoming a third thing.
 *
 * These are the owner's own soundscapes, transcribed from their phone: which stations, where the
 * faders sat, how much reverb. Channels point at [SeedStations] by stream URL rather than by row
 * id, because ids are assigned at insert time and a URL is the one part of a station that doesn't
 * move.
 *
 * Order here is the order they appear on the Mixes page.
 */
object SeedMixes {

    /**
     * One station's place in a shipped mix. Everything not named is flat — no EQ, no delay — which
     * is how all three of these were built.
     */
    data class Channel(
        val streamUrl: String,
        val fader: Float,
        val reverb: Float = 0f,
        val delay: Float = 0f,
        val delayMs: Float = ChannelFilters.DEFAULT_DELAY_MS,
        val toneLow: Float = 0f,
        val toneMid: Float = 0f,
        val toneHigh: Float = 0f,
    )

    data class Preset(val name: String, val channels: List<Channel>)

    val LIST = listOf(
        Preset(
            // Ritual ambient under plainsong: the same music twice, eight hundred years apart.
            name = "Ritual Gregorian",
            channels = listOf(
                Channel(SeedStations.RADCAP_RITUAL, fader = 0.62f),
                // The chants sit above it and in a bigger room than the drone they're over.
                Channel(SeedStations.GREGORIAN_CHANTS, fader = 0.77f, reverb = 0.65f),
            ),
        ),
        Preset(
            // Late-night conspiracy radio, breathing, and a dark drone under both.
            name = "The ULTIMATE Art Bell",
            channels = listOf(
                Channel(SeedStations.RADCAP_RITUAL, fader = 0.65f),
                Channel(SeedStations.SEX_SOUND, fader = 0.75f),
                // A 32 kbps phone-in from 1997 wants a room around it.
                Channel(SeedStations.ART_BELL, fader = 0.75f, reverb = 0.45f),
            ),
        ),
        Preset(
            // Not a soundscape — just the station this app is named after, on its own.
            name = "Tasty Radio",
            channels = listOf(
                Channel(SeedStations.TASTY_RADIO, fader = 0.75f),
            ),
        ),
    )
}
