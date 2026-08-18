package com.tastyradio.data

/**
 * The stations a fresh install arrives with, with stream URLs resolved from radio-browser.info.
 *
 * Two groups, in this order:
 *
 * 1. **The stations the shipped mixes are built from** ([SeedMixes]) — the app is handed to people
 *    who should hear the point of it within a tap of opening it, so the ingredients sit at the top.
 * 2. **The owner's own list**, carried over from Transistor.
 *
 * Note what's in here, because it's the test suite for "streaming radio is messy": cleartext
 * `http://` (ABC, Gove FM, Radio Art, RadCap, Art Bell), a `livestream-redirect` URL that 302s
 * elsewhere (Gove FM), a non-standard port (RadCap, Art Bell), and an HLS `.m3u8` playlist rather
 * than a raw stream (ABC's HQ feed, kept as a second entry deliberately). If all of these play, the
 * plumbing is right.
 */
object SeedStations {

    /** The mate's station the app is named after. Its artwork is our own launcher icon. */
    const val TASTY_RADIO = "https://radio.aleph-art.com/listen/tastiest/radio.mp3"
    const val RADCAP_RITUAL = "http://79.120.39.202:8000/darkambient"
    const val SEX_SOUND = "https://sexsoundradio.com:8000/radio.mp3"
    const val ART_BELL = "http://stream.willstare.com:8450/"
    const val GREGORIAN_CHANTS = "http://air.radioart.online/hGregorian_chants.mp3" +
        "?dlid=db5ceb796b3f6b3b40cb449ed670f317"

    val LIST = listOf(
        Station(
            name = "Tasty Radio",
            streamUrl = TASTY_RADIO,
            // Millais's Ophelia — the artwork on the station itself, and the reason it's also this
            // app's launcher icon. Bundled rather than fetched: the one station that should never
            // show a placeholder is the one the app is named after. An *asset* rather than the
            // drawable it duplicates, because Coil's android.resource:// support is numeric-id only
            // (verified in 3.5.0) and a baked-in resource id is exactly the thing that goes stale.
            imageUrl = "file:///android_asset/ophelia.png",
            source = "manual",
            tags = "techno,jungle,house,juke,dub,jazz,rock,folk",
            codec = "MP3",
            bitrate = 320,
            sortOrder = 0,
        ),
        Station(
            name = "RADCAP: INDUSTRIAL / DARK / RITUAL AMBIENT",
            streamUrl = RADCAP_RITUAL,
            imageUrl = "http://radcap.ru/stylegraf/ritamb-c.jpg",
            sourceUuid = "0a66e47d-eabc-47ce-83e1-12fe7dcbda7d",
            source = "radio-browser",
            tags = "dark ambient,industrial,ritual",
            codec = "AAC+",
            bitrate = 320,
            country = "Russia",
            sortOrder = 1,
        ),
        Station(
            name = "Sex Sound Radio",
            streamUrl = SEX_SOUND,
            // The directory's favicon for this one is a genuine Windows .ico, which Android can't
            // decode. The site's apple-touch-icon is the same logo as a 180px PNG.
            imageUrl = "https://sexsoundradio.com/apple-touch-icon.png",
            sourceUuid = "81a9eb5b-2da6-472c-a6c7-1ebe3f3e87c4",
            source = "radio-browser",
            tags = "adult,asmr,erotic,explicit,female voices,noise,voices",
            codec = "MP3",
            bitrate = 128,
            country = "Moldova",
            sortOrder = 2,
        ),
        Station(
            name = "The Ultimate Art Bell",
            streamUrl = ART_BELL,
            // No artwork anywhere for this one — the monogram fallback earns its keep.
            sourceUuid = "1125f06b-01ff-4794-b347-e83128ec4a9f",
            source = "radio-browser",
            tags = "art bell,coast to coast am,conspiracies,conspiracy theories,paranormal",
            codec = "MP3",
            bitrate = 32,
            country = "United States",
            language = "english",
            sortOrder = 3,
        ),
        Station(
            // The ?dlid= token is not decoration: without it this host answers 401. Radio-browser
            // hands the token out as part of the resolved URL, so keep it.
            name = "Radio Art - Gregorian Chants",
            streamUrl = GREGORIAN_CHANTS,
            // No favicon in the directory for this one either.
            sortOrder = 4,
        ),
        Station(
            name = "ABC Radio National",
            streamUrl = "http://abc.streamguys1.com/live/rnnsw/icecast.audio",
            imageUrl = "http://www.abc.net.au/core-assets/radionational/favicon-32x32.png",
            sortOrder = 5,
        ),
        Station(
            name = "Ethereal Radio",
            streamUrl = "https://s5.radio.co/saac615442/listen",
            imageUrl = "https://pbs.twimg.com/profile_images/1461828969821581325/I9NhigQp_400x400.jpg",
            sortOrder = 6,
        ),
        Station(
            name = "Gove FM - Nhulunbuy 106.9",
            streamUrl = "http://playerservices.streamtheworld.com/api/livestream-redirect/8EAR.mp3",
            imageUrl = "https://i.ibb.co/7xD4wbj4/274663470-10159198533677763-3441138051921838720-n.jpg",
            sortOrder = 7,
        ),
        Station(
            name = "Radio Vaticana English",
            streamUrl = "https://radio.vaticannews.va/stream-en",
            imageUrl = "https://media.vaticannews.va/media/content/dam-archive/vaticannews/" +
                "multimedia/2021/02/09/2021.02.09-Logo-Radio-Vaticana.jpg/_jcr_content/" +
                "renditions/cq5dam.thumbnail.cropped.1000.563.jpeg",
            sortOrder = 8,
        ),
        Station(
            name = "Resonance 104.4FM",
            streamUrl = "https://stream.resonance.fm/resonance",
            imageUrl = "https://www.resonancefm.com/assets/" +
                "logo-a79206a34394173ba3e41d66a3388f4c.png",
            sortOrder = 9,
        ),
    )
}
