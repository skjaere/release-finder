package io.skjaere.releases.search

import io.skjaere.newznab.Indexer
import io.skjaere.newznab.IndexerCaps
import io.skjaere.newznab.IndexerOutcome
import io.skjaere.newznab.Newznab
import io.skjaere.newznab.SearchQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A [ReleaseSearch] of the indexers themselves, through [newznab]'s [Newznab.searchAll]: every enabled indexer at once,
 * its answer reused for a while unless `fresh`. Each item found is a [Release] with no availability yet; wrap this in a
 * [CheckedSearch] to check torrents against a debrid service.
 */
public class IndexerSearch(private val newznab: Newznab) : ReleaseSearch {
    override fun search(indexers: List<Indexer>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<SearchOutcome> =
        newznab.searchAll(indexers, fresh) { plan(caps)?.let { query(it) } ?: skip() }.map { outcome ->
            when (outcome) {
                is IndexerOutcome.Results ->
                    SearchOutcome.Results(outcome.indexer, outcome.query, outcome.page, outcome.page.items.map { Release(it) })
                is IndexerOutcome.Failed -> SearchOutcome.Failed(outcome.indexer, outcome.error)
                is IndexerOutcome.Skipped -> SearchOutcome.Skipped(outcome.indexer, outcome.reason)
            }
        }
}
