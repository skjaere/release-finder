package io.skjaere.releases.search

import io.skjaere.newznab.Indexer
import io.skjaere.newznab.IndexerCaps
import io.skjaere.newznab.SearchQuery
import kotlinx.coroutines.flow.Flow

/**
 * Searches indexers for releases: each enabled indexer of [search]'s `indexers` with the query `plan` picks from its caps,
 * or skipped where `plan` gives null, each indexer's [SearchOutcome] emitted as it arrives. [IndexerSearch] searches
 * through newznab-kotlin; [CheckedSearch] adds torrents' cache checks to another search.
 */
public fun interface ReleaseSearch {
    /** Searches [indexers], each by the query [plan] picks from its caps (null skips it); [fresh] reuses no cached answer. */
    public fun search(indexers: List<Indexer>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<SearchOutcome>
}

/**
 * [ReleaseSearch.search] reusing cached answers: a functional interface's method can't give `fresh` a default value, so
 * this stands for it.
 */
public fun ReleaseSearch.search(indexers: List<Indexer>, plan: (IndexerCaps) -> SearchQuery?): Flow<SearchOutcome> =
    search(indexers, false, plan)
