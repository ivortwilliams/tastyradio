package com.tastyradio.share

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.tastyradio.data.Station
import com.tastyradio.playback.ChannelFilters
import com.tastyradio.playback.Mixer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.roundToInt

/**
 * A mix, in a link.
 *
 * The whole point of this app is that two unrelated stations become a third thing — and a third
 * thing you can't hand to anybody is half a feature. This is that handing-over, and it is the same
 * format as the web version: `web/client/src/data/share.ts` writes and reads exactly these bytes.
 * A link made on a phone opens in a browser and the other way round.
 *
 * ## The shape
 *
 * ```
 * https://radio.truthseekersbyo.com/m#<payload>
 * ```
 *
 * **The payload is in the fragment, deliberately.** Fragments are never sent to a server, so a mix
 * you send someone is between the two of you — no row in a database, no id to look up, nothing in
 * an access log. It also means there is nothing to keep alive and nothing to expire: the link *is*
 * the mix, so it works for as long as the stations do. That matters more here than it looks,
 * because this app has never had accounts or server-side user data and this feature was not going
 * to be the thing that introduced them.
 *
 * **The path is `/m` so this app can claim it.** An App Links filter matches on host and path,
 * never on the fragment; claiming the whole domain would mean every link to the site opened the app
 * instead of the site. The manifest verifies against `/.well-known/assetlinks.json`, which the web
 * server serves with this build's signing fingerprint in it — re-sign with a different key and that
 * file needs updating, or phones quietly stop claiming the links and fall back to the browser.
 * Which is a soft landing rather than a break: the web desk opens the same link.
 *
 * ## The payload
 * A marker character, then base64url:
 *
 * - `d` — raw-deflated JSON, which is what both platforms write.
 * - `j` — the same JSON uncompressed, for a browser without `CompressionStream`.
 *
 * Raw deflate — `Deflater(level, nowrap = true)` — because that is the one the browser calls
 * `deflate-raw`. The plain `Deflater(level)` writes a zlib header the browser's `deflate-raw`
 * refuses, and the failure looks like a corrupt link rather than a mismatch.
 *
 * Keys are short and defaults are omitted, because a four-channel mix pasted into a chat app should
 * be one line rather than a paragraph. `v` is the format version; a reader that meets a version it
 * doesn't know refuses rather than guessing.
 */
object MixLink {

    const val HOST = "radio.truthseekersbyo.com"
    const val PATH = "/m"

    /** One station's place in a shared mix — everything the mixer needs to rebuild the channel. */
    data class Channel(
        val station: Station,
        val fader: Float,
        val muted: Boolean,
        val tone: Mixer.Tone,
    )

    data class Shared(val name: String, val channels: List<Channel>)

    // ---------------------------------------------------------------- writing

    /** The link to hand somebody. */
    fun link(name: String, channels: List<Channel>): String = "https://$HOST$PATH#${encode(name, channels)}"

    fun encode(name: String, channels: List<Channel>): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("n", name.trim().take(120))

        val array = JSONArray()
        for (channel in channels) array.put(toWire(channel))
        root.put("c", array)

        val json = root.toString().toByteArray(Charsets.UTF_8)
        return "d" + Base64.encodeToString(deflate(json), BASE64_FLAGS)
    }

    private fun toWire(channel: Channel): JSONObject {
        val wire = JSONObject()
        wire.put("u", channel.station.streamUrl)
        wire.put("n", channel.station.name.take(120))
        wire.put("f", round(channel.fader))

        // A phone's own artwork is a `content://` URI from its photo picker and means nothing on
        // anyone else's device; only a real fetchable image travels.
        val image = channel.station.imageUrl
        if (image != null && image.startsWith("http", ignoreCase = true) && image.length <= 300) {
            wire.put("i", image)
        }
        channel.station.sourceUuid?.let { wire.put("id", it) }
        channel.station.tags?.take(200)?.let { wire.put("tg", it) }

        if (channel.muted) wire.put("m", 1)
        val tone = channel.tone
        if (tone.low != 0f) wire.put("lo", round(tone.low))
        if (tone.mid != 0f) wire.put("md", round(tone.mid))
        if (tone.high != 0f) wire.put("hi", round(tone.high))
        if (tone.reverb != 0f) wire.put("rv", round(tone.reverb))
        if (tone.delay != 0f) wire.put("dl", round(tone.delay))
        if (tone.delay != 0f && tone.delayMs != ChannelFilters.DEFAULT_DELAY_MS) {
            wire.put("dm", tone.delayMs.roundToInt())
        }
        return wire
    }

    // ---------------------------------------------------------------- reading

    /**
     * A mix out of an intent: a tapped link, or a link somebody shared *into* the app.
     *
     * The second one matters more than it sounds. Links arrive inside WhatsApp and Messenger as
     * often as they arrive as taps, and those apps love to open a link in their own browser — from
     * which "Share → Tasty Radio" is the way out.
     */
    fun from(intent: Intent?): Shared? {
        val raw = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return null
        return fromText(raw)
    }

    /**
     * Finds the mix in whatever text it arrived in.
     *
     * Shared text is rarely just a URL — chat apps bolt on "sent from…" and people type around the
     * link — so the payload is pulled out rather than assumed to be the whole string.
     */
    fun fromText(text: String): Shared? {
        val payload = LINK.find(text)?.groupValues?.get(1)
            ?: text.trim().takeIf { BARE.matches(it) }
            ?: return null
        return decode(payload)
    }

    fun decode(payload: String): Shared? {
        val trimmed = payload.trim().removePrefix("#")
        if (trimmed.length < 2) return null

        val bytes = try {
            Base64.decode(trimmed.substring(1), BASE64_FLAGS)
        } catch (error: IllegalArgumentException) {
            return null
        }

        val json = when (trimmed.first()) {
            'd' -> inflate(bytes)
            'j' -> String(bytes, Charsets.UTF_8)
            else -> null
        } ?: return null

        return try {
            fromWire(JSONObject(json))
        } catch (error: Exception) {
            null
        }
    }

    private fun fromWire(root: JSONObject): Shared? {
        if (root.optInt("v") != 1) return null
        val array = root.optJSONArray("c") ?: return null

        val channels = mutableListOf<Channel>()
        for (index in 0 until array.length()) {
            val wire = array.optJSONObject(index) ?: continue
            val url = wire.optString("u")
            if (!url.startsWith("http", ignoreCase = true)) continue

            channels += Channel(
                station = Station(
                    // No row of its own: a shared mix carries stations, not references into
                    // someone else's collection. `Station.channelKey()` falls back to the stream
                    // URL for exactly this, which is also what an auditioned search result does.
                    id = 0,
                    name = wire.optString("n").ifBlank { url }.take(120),
                    streamUrl = url,
                    imageUrl = wire.optString("i").ifBlank { null },
                    sourceUuid = wire.optString("id").ifBlank { null },
                    source = "shared",
                    tags = wire.optString("tg").ifBlank { null },
                ),
                fader = wire.optDouble("f", 1.0).toFloat().coerceIn(0f, 1f),
                muted = wire.optInt("m") == 1,
                tone = Mixer.Tone(
                    low = wire.optDouble("lo", 0.0).toFloat().coerceIn(-1f, 1f),
                    mid = wire.optDouble("md", 0.0).toFloat().coerceIn(-1f, 1f),
                    high = wire.optDouble("hi", 0.0).toFloat().coerceIn(-1f, 1f),
                    reverb = wire.optDouble("rv", 0.0).toFloat().coerceIn(0f, 1f),
                    delay = wire.optDouble("dl", 0.0).toFloat().coerceIn(0f, 1f),
                    delayMs = wire.optDouble("dm", ChannelFilters.DEFAULT_DELAY_MS.toDouble())
                        .toFloat()
                        .coerceIn(ChannelFilters.MIN_DELAY_MS, ChannelFilters.MAX_DELAY_MS),
                ),
            )
        }
        if (channels.isEmpty()) return null

        val name = root.optString("n").trim().ifEmpty { "A shared mix" }
        return Shared(name = name, channels = channels)
    }

    // ---------------------------------------------------------------- handing it over

    /** The system share sheet, which is how a link gets to a person. */
    fun share(context: Context, name: String, channels: List<Channel>) {
        val link = link(name, channels)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$name — a Tasty Radio mix\n$link")
            putExtra(Intent.EXTRA_SUBJECT, "$name — a Tasty Radio mix")
        }
        context.startActivity(Intent.createChooser(intent, "Share “$name”"))
    }

    // ---------------------------------------------------------------- bytes

    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    private val LINK = Regex("""https?://\S*#([A-Za-z0-9_\-]{4,})""")
    private val BARE = Regex("""[A-Za-z0-9_\-]{8,}""")

    /** Three decimals is finer than any fader you can move, and shorter than a float's full print. */
    private fun round(value: Float): Double = (value * 1000f).roundToInt() / 1000.0

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap = */ true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): String? {
        val inflater = Inflater(/* nowrap = */ true)
        return try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                // A truncated link stops here rather than spinning: `inflate` returns 0 and asks
                // for input that is never coming.
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, read)
            }
            if (out.size() == 0) null else out.toString("UTF-8")
        } catch (error: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
