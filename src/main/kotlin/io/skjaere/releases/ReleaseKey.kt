package io.skjaere.releases

import java.util.Locale

/**
 * What a release is known by wherever it is kept or matched: a search's results, the ranker's facts, a remembered
 * release. A torrent is its info hash, `btih:<hex>`, so one listed by several indexers is one release, as a debrid
 * service caches it; any other is its indexer and its guid there, `indexerId/guid`, which a slug guid two indexers share
 * can't be alone. A torrent remembered without its hash has that key for its guid, which is its key then.
 */
public object ReleaseKey {
    /** What a torrent's key starts with, its info hash after it. */
    public const val TORRENT: String = "btih:"

    /** The key of the release [indexerId] lists as [guid], with the torrent's [infoHash] (40 hex digits) where it has one. */
    public fun of(indexerId: String, guid: String, infoHash: String? = null): String =
        infoHash?.takeIf { it.isNotBlank() }?.let { TORRENT + it.lowercase(Locale.ROOT) }
            ?: guid.takeIf { CONTENT_ID.matches(it) }?.lowercase(Locale.ROOT)
            ?: "$indexerId/$guid"

    /** A torrent's content id as its guid holds it: `btih:` and 40 hex digits. */
    private val CONTENT_ID = Regex("""btih:[0-9a-fA-F]{40}""")
}
