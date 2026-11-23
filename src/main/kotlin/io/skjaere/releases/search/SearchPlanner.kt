package io.skjaere.releases.search

import io.skjaere.newznab.IndexerCaps
import io.skjaere.newznab.SearchQuery
import io.skjaere.newznab.SearchType
import io.skjaere.newznab.TvIds

/** A title the user picked, e.g. from TMDB's suggestions, with the ids to search it by. */
public data class PickedTitle(val name: String, val year: Int?, val isTv: Boolean, val ids: TvIds)

/** What a search asks each indexer, for typed text or a picked title. */
public object SearchPlanner {
    /**
     * The search text once the user picks the title [name] while [typed] is what they typed. A show keeps the
     * season/episode typed ("lanternkeepers 2x05" -> "The Lanternkeepers S02E05"), so [plan] searches for it.
     */
    public fun pickedText(typed: String, name: String, isTv: Boolean): String =
        listOfNotNull(name, QueryParser.parse(typed).episodeTag?.takeIf { isTv }).joinToString(" ")

    /**
     * Per indexer: by id where its caps allow (picked title), else by name with season/episode. A pasted
     * release name is searched as it is: tv-search would ask for the words around its episode.
     */
    public fun plan(caps: IndexerCaps, q: ParsedQuery, title: PickedTitle?): SearchQuery? {
        if (title?.isTv == false) {
            val imdb = title.ids.imdb?.takeIf { caps.supports(SearchType.Movie, "imdbid") }
            val tmdb = title.ids.tmdb?.takeIf { caps.supports(SearchType.Movie, "tmdbid") && imdb == null }
            return when {
                imdb != null || tmdb != null -> SearchQuery.Movie(imdb = imdb, tmdb = tmdb)
                caps.supports(SearchType.Movie, "q") -> SearchQuery.Movie(query = title.name)
                else -> SearchQuery.Text(listOfNotNull(title.name, title.year?.toString()).joinToString(" "))
            }
        }
        if (title?.isTv == true) {
            val ids = title.ids
            val byId = listOfNotNull(
                ids.tvdb?.takeIf { caps.supports(SearchType.Tv, "tvdbid") }?.let { TvIds(tvdb = it) },
                ids.tmdb?.takeIf { caps.supports(SearchType.Tv, "tmdbid") }?.let { TvIds(tmdb = it) },
                ids.imdb?.takeIf { caps.supports(SearchType.Tv, "imdbid") }?.let { TvIds(imdb = it) },
            ).firstOrNull()
            return when {
                byId != null -> SearchQuery.Tv(ids = byId, season = q.season, episode = q.episode)
                caps.supports(SearchType.Tv, "q") -> SearchQuery.Tv(query = title.name, season = q.season, episode = q.episode)
                else -> SearchQuery.Text(listOfNotNull(title.name, q.episodeTag).joinToString(" "))
            }
        }
        return when {
            q.season != null && !q.isReleaseName && caps.supports(SearchType.Tv, "q") ->
                SearchQuery.Tv(query = q.title, season = q.season, episode = q.episode.takeIf { caps.supports(SearchType.Tv, "ep") })
            else -> SearchQuery.Text(q.raw)
        }
    }
}
