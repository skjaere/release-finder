package io.skjaere.releases.search

import io.skjaere.newznab.Indexer
import io.skjaere.releases.RankingContext
import java.security.MessageDigest
import java.util.Base64

/** What one [EpisodeReleaseFinder.find] is asked; its result holds only while all of it is unchanged. */
public data class EpisodeSearch(
    val request: EpisodeRequest,
    val indexers: List<Indexer>,
    val context: RankingContext,
    val remembered: RememberedPick? = null,
)

/**
 * Hands a background search for an episode (one started before the current episode ends, to play the next) to the play
 * that follows it. The next [take] empties it, and returns the result only for the same search made within [maxAgeMs]:
 * the episode, the indexers (ids, URLs, enabled, API keys, User-Agents), the ranking and the remembered release. An
 * indexer can answer by its User-Agent, so a result asked with another one is not this search's. A result with nothing
 * playable or an indexer error is never held, so a retry after a network failure, or for an episode whose releases
 * haven't appeared yet, searches again.
 */
public class EpisodeFindHandoff(
    private val maxAgeMs: Long = 10 * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** An [EpisodeSearch] with each API key as a hash. */
    private data class Fingerprint(
        val request: EpisodeRequest,
        val indexers: List<IndexerPrint>,
        val context: RankingContext,
        val remembered: RememberedPick?,
    )

    /** [userAgent] trimmed, as the client sends it. */
    private data class IndexerPrint(val id: String, val url: String, val enabled: Boolean, val keyHash: String, val userAgent: String)

    private class Held(val fingerprint: Fingerprint, val result: FindResult, val atMs: Long)

    private var held: Held? = null

    /** Holds [result], found for [search], for the next [take]; one with nothing playable or an indexer error clears it. */
    @Synchronized
    public fun offer(search: EpisodeSearch, result: FindResult) {
        val worthKeeping = result.playable.isNotEmpty() && result.stages.none { stage -> stage.indexers.any { it.error != null } }
        held = if (worthKeeping) Held(fingerprint(search), result, now()) else null
    }

    /** The result held for [search], if it is the same search and not too old; either way nothing is held after. */
    @Synchronized
    public fun take(search: EpisodeSearch): FindResult? {
        val h = held ?: return null
        held = null
        return h.result.takeIf { h.fingerprint == fingerprint(search) && now() - h.atMs < maxAgeMs }
    }

    /**
     * Whether a [take] for [request] would get the held result, also [forMs] from now, when the
     * rest of its search is unchanged: tells a caller the result is gone without the whole search.
     */
    @Synchronized
    public fun holds(request: EpisodeRequest, forMs: Long = 0): Boolean {
        val h = held ?: return false
        return h.fingerprint.request == request && now() + forMs - h.atMs < maxAgeMs
    }

    @Synchronized
    public fun clear() {
        held = null
    }

    private fun fingerprint(search: EpisodeSearch) = Fingerprint(
        search.request,
        search.indexers.map { IndexerPrint(it.id, it.url, it.enabled, sha256(it.apiKey), it.userAgent.trim()) },
        search.context,
        search.remembered,
    )

    private fun sha256(s: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
