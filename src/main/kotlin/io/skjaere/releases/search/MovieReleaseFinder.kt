package io.skjaere.releases.search

import io.skjaere.newznab.Indexer
import io.skjaere.newznab.SearchQuery
import io.skjaere.newznab.TvIds
import io.skjaere.releases.RankingContext
import io.skjaere.releases.ReleaseParser
import io.skjaere.releases.ReleaseRanker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * A movie to find releases for: its [title] and [year], and the ids indexers can search it by. Its releases without a
 * language tag speak its [originalLanguage] (ISO 639-1, e.g. from TMDB), and their bitrates are estimated over its
 * [runtimeMinutes].
 */
public data class MovieRequest(
    val title: String,
    val year: Int? = null,
    /** IMDb id, `tt` and its digits. */
    val imdb: String? = null,
    val tmdb: Int? = null,
    val originalLanguage: String? = null,
    val runtimeMinutes: Int? = null,
)

/**
 * What the indexers found for a movie: its releases, ranked as one list and split into those this device can play
 * ([playable], best first) and those it can't ([hidden]), and what each indexer did ([indexers], in the order they first
 * answered). A torrent indexer's report counts its torrents as found and accepted from the start, though they are
 * releases only once checked.
 */
public data class MovieFindResult(
    val request: MovieRequest,
    val playable: List<RankedItem>,
    val hidden: List<RankedItem>,
    val indexers: List<IndexerReport>,
)

/**
 * Finds releases of one movie across the user's indexers: each by the movie's IMDb or TMDB id where its caps allow, else
 * by name ([SearchPlanner]). An indexer's own match is trusted for an id search; a name search's results must be the
 * movie by name and year ([TitleMatcher]). The releases taken are ranked as one group ([ResultGrouper]), whatever year
 * (or none) they are tagged with. Safe to collect on the main thread: searching, checking names and ranking run on
 * [defaultDispatcher].
 */
public class MovieReleaseFinder(
    private val search: ReleaseSearch,
    ranker: ReleaseRanker = ReleaseRanker(),
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val grouper = ResultGrouper(ranker)

    /**
     * Searches [indexers] for [request], ranked by [context]; [fresh] reuses no cached answer. The result comes once each
     * enabled indexer has answered, a torrent indexer once it says its torrents are being checked; then again each time
     * some of those settle, the cached ones joining the list. The last result is the whole search's.
     */
    public fun find(request: MovieRequest, indexers: List<Indexer>, context: RankingContext, fresh: Boolean = false): Flow<MovieFindResult> = flow {
        val picked = PickedTitle(request.title, request.year, isTv = false, TvIds(tmdb = request.tmdb, imdb = request.imdb))
        // A movie's plan reads only the picked title; the parsed text stands for nothing typed.
        val typed = QueryParser.parse(request.title)
        val answers = Answers(request)
        val enabled = indexers.count { it.enabled }
        val order = indexers.map { it.id }
        var shown = false
        search.search(indexers, fresh) { caps -> SearchPlanner.plan(caps, typed, picked) }.collect { outcome ->
            answers.take(outcome)
            if (answers.reports.size >= enabled && (!shown || outcome is SearchOutcome.Checked)) {
                emit(answers.result(context, order))
                shown = true
            }
        }
        if (!shown) emit(answers.result(context, order))
        // Checking names (a release-name parse per item) and ranking stay off the caller's thread.
    }.flowOn(defaultDispatcher)

    /**
     * One search's answers: each indexer's report, in the order they first answered, and the releases taken ([accepted]).
     * Results an indexer found by name, having no id to search by, must actually be the movie. A torrent indexer's
     * torrents count as found once it answers, and are taken as their checks settle.
     */
    private inner class Answers(private val request: MovieRequest) {
        private val byIndexer = LinkedHashMap<String, IndexerReport>()

        /** The keys of each torrent indexer's accepted torrents still being checked. */
        private val waiting = HashMap<String, MutableSet<String>>()

        /** Every release taken so far. */
        private val accepted = mutableListOf<Release>()

        val reports: Collection<IndexerReport> get() = byIndexer.values

        fun take(outcome: SearchOutcome) {
            val indexer = outcome.indexer
            byIndexer[indexer.id] = when (outcome) {
                is SearchOutcome.Results -> {
                    val found = outcome.releases.filter { accepts(it, outcome.query) }
                    accepted += found
                    IndexerReport(indexer, describe(outcome.query), outcome.releases.size, found.size)
                }
                is SearchOutcome.Failed -> IndexerReport(indexer, null, error = outcome.error)
                is SearchOutcome.Skipped -> IndexerReport(indexer, null, skipped = outcome.reason)
                is SearchOutcome.Checking -> {
                    val found = outcome.found.filter { accepts(it, outcome.query) }
                    waiting[indexer.id] = found.map { it.key }.toMutableSet()
                    IndexerReport(indexer, describe(outcome.query), outcome.found.size, found.size)
                }
                is SearchOutcome.Checked -> {
                    // Only those it accepted, each once; its report stays as it was.
                    val left = waiting[indexer.id] ?: return
                    accepted += outcome.releases.filter { left.remove(it.key) }
                    return
                }
            }
        }

        /** The movie's releases taken so far, as one group ranked by [context]. */
        fun result(context: RankingContext, order: List<String>): MovieFindResult {
            val known = ResultGrouper.KnownTitle(request.title, request.year, isTv = false, request.originalLanguage, request.runtimeMinutes)
            val group = grouper.group(accepted.toList(), context, known, order).movieGroup
            return MovieFindResult(request, group?.playable.orEmpty(), group?.hidden.orEmpty(), reports.toList())
        }

        private fun accepts(release: Release, query: SearchQuery): Boolean =
            describe(query) != BY_NAME || TitleMatcher.matches(ReleaseParser.parse(release.item.title), request.title, request.year)
    }

    private companion object {
        const val BY_NAME = "name"

        /** How an indexer was asked: by an id the query holds, else by [BY_NAME]. */
        fun describe(query: SearchQuery): String = when {
            query is SearchQuery.Movie && query.imdb != null -> "IMDb id"
            query is SearchQuery.Movie && query.tmdb != null -> "TMDB id"
            query is SearchQuery.Tv && query.ids.tvdb != null -> "TVDB id"
            query is SearchQuery.Tv && query.ids.tmdb != null -> "TMDB id"
            query is SearchQuery.Tv && query.ids.imdb != null -> "IMDb id"
            query is SearchQuery.Tv && query.ids.tvmaze != null -> "TVmaze id"
            else -> BY_NAME
        }
    }
}
