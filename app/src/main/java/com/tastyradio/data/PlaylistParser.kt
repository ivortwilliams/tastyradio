package com.tastyradio.data

/**
 * Parses M3U and PLS playlists into stations.
 *
 * The fast way to get a real station list into this app is Transistor's own *Export M3U*, so
 * import comes before search in the build order. Both formats are trivially simple and both are
 * routinely malformed in the wild, so this is deliberately forgiving: anything that looks like a
 * URL counts, anything that doesn't is ignored.
 */
object PlaylistParser {

    data class Entry(val name: String, val url: String)

    fun parse(text: String): List<Entry> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val looksLikePls = lines.any { it.equals("[playlist]", ignoreCase = true) } ||
            lines.any { it.startsWith("File", ignoreCase = true) && it.contains('=') }
        return if (looksLikePls) parsePls(lines) else parseM3u(lines)
    }

    /**
     * `#EXTINF:-1,Station Name` followed by the stream URL. A URL with no preceding #EXTINF still
     * counts — it just gets its host as a name.
     */
    private fun parseM3u(lines: List<String>): List<Entry> {
        val out = mutableListOf<Entry>()
        var pendingName: String? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingName = line.substringAfter(',', "").trim().ifEmpty { null }
                }
                line.startsWith("#") -> Unit // #EXTM3U, comments, extensions we don't care about
                isUrl(line) -> {
                    out += Entry(pendingName ?: nameFromUrl(line), line)
                    pendingName = null
                }
            }
        }
        return out
    }

    /** `File1=<url>` / `Title1=<name>`, matched up by index. */
    private fun parsePls(lines: List<String>): List<Entry> {
        val files = sortedMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        for (line in lines) {
            val key = line.substringBefore('=', "").trim()
            val value = line.substringAfter('=', "").trim()
            if (value.isEmpty()) continue
            val index = key.dropWhile { !it.isDigit() }.toIntOrNull() ?: continue
            when {
                key.startsWith("File", ignoreCase = true) && isUrl(value) -> files[index] = value
                key.startsWith("Title", ignoreCase = true) -> titles[index] = value
            }
        }
        return files.map { (index, url) -> Entry(titles[index] ?: nameFromUrl(url), url) }
    }

    private fun isUrl(line: String) =
        line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true)

    private fun nameFromUrl(url: String): String =
        url.removePrefix("http://").removePrefix("https://").substringBefore('/').ifEmpty { url }
}
