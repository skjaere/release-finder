package io.skjaere.releases.search

import io.skjaere.newznab.Indexer
import io.skjaere.newznab.IndexerCaps
import io.skjaere.newznab.IndexerKind
import io.skjaere.newznab.SearchQuery
import io.skjaere.releases.Availability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Checks a torrent indexer's answer against the user's debrid services' caches, naming no service: gives [releases] back
 * with their [Release.availability] set, as they settle and each once: those it already knows, and those of an [indexer]
 * the user doesn't use with a debrid service ([Availability.NotForService]), at once; the rest as each service answers.
 */
public fun interface CacheCheck {
    public fun check(indexer: Indexer, releases: List<Release>): Flow<List<Release>>
}

/**
 * [search], with each torrent indexer's torrents checked as its answer comes: a Usenet indexer's outcome, and any but a
 * torrent indexer's results, pass at once as they came; a torrent indexer's results become [SearchOutcome.Checking] at
 * once, then a [SearchOutcome.Checked] each time some of its torrents settle, the last with none waiting. So nothing waits
 * for a check but that answer's own torrents. A check that fails, or runs past [bound], gives what is left as
 * [Availability.NotChecked]: hidden, never tried, and checked again by the next search.
 */
public class CheckedSearch(
    private val search: ReleaseSearch,
    private val bound: Duration = BOUND,
    private val check: CacheCheck,
) : ReleaseSearch {
    private val log = LoggerFactory.getLogger(CheckedSearch::class.java)

    override fun search(indexers: List<Indexer>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<SearchOutcome> = channelFlow {
        search.search(indexers, fresh, plan).collect { outcome ->
            val releases = (outcome as? SearchOutcome.Results)?.takeIf { it.indexer.kind == IndexerKind.Torznab }?.releases.orEmpty()
            if (releases.isEmpty()) {
                send(outcome)
                return@collect
            }
            outcome as SearchOutcome.Results
            val indexer = outcome.indexer
            val waiting = releases.associateBy { it.key }.toMutableMap()
            send(SearchOutcome.Checking(indexer, outcome.query, releases, waiting.size))
            // Each answer's check on its own: the next answer, and the outcome after it, never wait for this one.
            launch {
                try {
                    withTimeoutOrNull(bound.toMillis()) {
                        check.check(indexer, releases).collect { chunk ->
                            val settled = chunk.filter { waiting.remove(it.key) != null }
                            if (settled.isNotEmpty()) send(SearchOutcome.Checked(indexer, settled, waiting.size))
                        }
                    } ?: log.info("checking {}'s torrents: {} unsettled after {} s", indexer.name, waiting.size, bound.seconds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The check's own failure: its torrents can't be said to be cached, and the search goes on.
                    log.warn("checking {}'s torrents failed: {}", indexer.name, e.javaClass.simpleName)
                }
                if (waiting.isNotEmpty()) send(SearchOutcome.Checked(indexer, waiting.values.map { it.copy(availability = Availability.NotChecked) }, 0))
            }
        }
    }

    public companion object {
        /** How long a torrent indexer's answer waits for its check at most. */
        public val BOUND: Duration = Duration.ofSeconds(10)
    }
}
