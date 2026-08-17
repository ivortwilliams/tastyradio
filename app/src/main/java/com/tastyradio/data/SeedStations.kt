package com.tastyradio.data

/**
 * The owner's own stations, as visible in the Transistor reference screenshot, with stream URLs
 * resolved from radio-browser.info.
 *
 * Note what's in here, because it's the test suite for "streaming radio is messy": cleartext
 * `http://` (ABC, Gove FM, Radio Art), a `livestream-redirect` URL that 302s elsewhere (Gove FM),
 * and an HLS `.m3u8` playlist rather than a raw stream (ABC's HQ feed, kept as a second entry
 * deliberately). If all of these play, the plumbing is right.
 */
object SeedStations {

    val LIST = listOf(
        Station(
            name = "ABC Radio National",
            streamUrl = "http://abc.streamguys1.com/live/rnnsw/icecast.audio",
            imageUrl = "http://www.abc.net.au/core-assets/radionational/favicon-32x32.png",
            sortOrder = 0,
        ),
        Station(
            name = "Ethereal Radio",
            streamUrl = "https://s5.radio.co/saac615442/listen",
            imageUrl = "https://pbs.twimg.com/profile_images/1461828969821581325/I9NhigQp_400x400.jpg",
            sortOrder = 1,
        ),
        Station(
            name = "Gove FM - Nhulunbuy 106.9",
            streamUrl = "http://playerservices.streamtheworld.com/api/livestream-redirect/8EAR.mp3",
            imageUrl = "https://i.ibb.co/7xD4wbj4/274663470-10159198533677763-3441138051921838720-n.jpg",
            sortOrder = 2,
        ),
        Station(
            // The ?dlid= token is not decoration: without it this host answers 401. Radio-browser
            // hands the token out as part of the resolved URL, so keep it.
            name = "Radio Art - Gregorian Chants",
            streamUrl = "http://air.radioart.online/hGregorian_chants.mp3" +
                "?dlid=db5ceb796b3f6b3b40cb449ed670f317",
            // No favicon in the directory for this one — the monogram fallback earns its keep.
            sortOrder = 3,
        ),
        Station(
            name = "Radio Vaticana English",
            streamUrl = "https://radio.vaticannews.va/stream-en",
            imageUrl = "https://media.vaticannews.va/media/content/dam-archive/vaticannews/" +
                "multimedia/2021/02/09/2021.02.09-Logo-Radio-Vaticana.jpg/_jcr_content/" +
                "renditions/cq5dam.thumbnail.cropped.1000.563.jpeg",
            sortOrder = 4,
        ),
        Station(
            name = "Resonance 104.4FM",
            streamUrl = "https://stream.resonance.fm/resonance",
            imageUrl = "https://www.resonancefm.com/assets/" +
                "logo-a79206a34394173ba3e41d66a3388f4c.png",
            sortOrder = 5,
        ),
    )
}
