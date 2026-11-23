package io.skjaere.releases.search

import io.skjaere.newznab.Indexer
import io.skjaere.newznab.NewznabError
import io.skjaere.newznab.SearchPage
import io.skjaere.newznab.SearchQuery

/**
 * One indexer's part of a [ReleaseSearch]. Each indexer gives one [Results], [Failed] or [Skipped]; or, for a torrent
 * indexer whose torrents are being checked against a debrid service's cache ([CheckedSearch]), one [Checking] and then
 * [Checked] as they settle, the last with none [Checked.waiting].
 */
public sealed interface SearchOutcome {
    public val indexer: Indexer

    /** What [indexer] found for [query]: its [page] as the indexer answered it, and the page's items as [releases]. */
    public data class Results(
        override val indexer: Indexer,
        val query: SearchQuery,
        val page: SearchPage,
        val releases: List<Release>,
    ) : SearchOutcome

    public data class Failed(override val indexer: Indexer, val error: NewznabError) : SearchOutcome

    /** The indexer was left out of the search, e.g. as its caps don't list the search: [reason] says why. */
    public data class Skipped(override val indexer: Indexer, val reason: String) : SearchOutcome

    /**
     * A torrent indexer answered [query] with [found], whose cache check is under way: they come as [Checked] once each
     * settles. [waiting]: how many are still to settle.
     */
    public data class Checking(
        override val indexer: Indexer,
        val query: SearchQuery,
        val found: List<Release>,
        val waiting: Int,
    ) : SearchOutcome

    /** Some of a [Checking] indexer's releases settled, each with its [Release.availability]; [waiting] are left. */
    public data class Checked(override val indexer: Indexer, val releases: List<Release>, val waiting: Int) : SearchOutcome
}
