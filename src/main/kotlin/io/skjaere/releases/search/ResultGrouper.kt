package io.skjaere.releases.search

import io.skjaere.releases.Availability
import io.skjaere.releases.ParsedRelease
import io.skjaere.releases.RankedRelease
import io.skjaere.releases.RankingContext
import io.skjaere.releases.ReleaseParser
import io.skjaere.releases.ReleaseRanker
import io.skjaere.releases.Severity
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** A [release] as the ranker [ranked] it. */
public data class RankedItem(val release: Release, val ranked: RankedRelease) {
    public val key: String get() = release.key
    public val parsed: ParsedRelease get() = ranked.parsed
}

/** What a [ResultGroup] holds: one episode (or a daily show's day), one season pack, one movie, or a release of neither. */
public enum class GroupKind { Episode, SeasonPack, Movie, Other }

/** Releases of one episode, one season pack or one movie; [playable] is ranked best first. */
public data class ResultGroup(
    /** Unique within one [GroupedResults]: the section's key and the episode/pack/movie, e.g. "harbor lights|ep:2:5". */
    val key: String,
    val kind: GroupKind,
    val season: Int?,
    val episodes: List<Int>,
    val airDate: LocalDate?,
    val year: Int?,
    val playable: List<RankedItem>,
    val hidden: List<RankedItem>,
) {
    public val best: RankedItem? get() = playable.firstOrNull()
    public val size: Int get() = playable.size + hidden.size

    /** "S02E05", "S02E05–E06", "Season 2", "2026-10-12", "2019" or the release's title. */
    public val label: String
        get() = when (kind) {
            // ASCII digits whatever the default locale.
            GroupKind.Episode -> airDate?.toString() ?: ("S%02dE%02d".format(Locale.ROOT, season ?: 0, episodes.first()) +
                if (episodes.size > 1) "–E%02d".format(Locale.ROOT, episodes.last()) else "")
            GroupKind.SeasonPack -> "Season $season"
            GroupKind.Movie -> year?.toString() ?: "Movie"
            GroupKind.Other -> (playable + hidden).first().release.item.title
        }
}

/** All groups of one title ("Harbor Lights", "Harbor Lights (1998)"). */
public data class ResultSection(val key: String, val title: String, val year: Int?, val groups: List<ResultGroup>) {
    public val hiddenCount: Int get() = groups.sumOf { it.hidden.size }
}

/** A search's releases as titles ([sections]), each with its episodes, packs or movies. */
public data class GroupedResults(val sections: List<ResultSection>) {
    public val hidden: List<RankedItem> get() = sections.flatMap { s -> s.groups.flatMap { it.hidden } }
    public val playableCount: Int get() = sections.sumOf { s -> s.groups.sumOf { it.playable.size } }
    public val isEmpty: Boolean get() = sections.isEmpty()

    /** The group with this [ResultGroup.key], e.g. the one the user opened. */
    public fun group(key: String): ResultGroup? = sections.flatMap { it.groups }.firstOrNull { it.key == key }

    /** With a picked movie (a [ResultGrouper.KnownTitle] that isn't TV), the movie's own group: its releases, ranked as one. */
    public val movieGroup: ResultGroup? get() = sections.firstOrNull()?.groups?.firstOrNull { it.kind == GroupKind.Movie }

    /** "HEVC 10-bit, DD+ audio": the commonest reasons releases were hidden. */
    public fun hiddenReasons(limit: Int = 2): List<String> = hidden.hiddenReasons(limit)
}

/** "HEVC 10-bit, Not cached": the commonest reasons [this], hidden releases, were hidden, the commonest first. */
public fun List<RankedItem>.hiddenReasons(limit: Int = 2): List<String> =
    flatMap { r -> r.ranked.reasons.filter { it.severity == Severity.Blocking }.map { it.text.substringBefore(": no decoder") } }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(limit).map { it.key }

/**
 * Turns raw search results into titles → episodes/packs/movies → ranked releases.
 * With a [KnownTitle] (a title the user picked, e.g. from TMDB's suggestions) every result belongs to that title, and a
 * picked movie's releases form one group, whatever year (or none) they are tagged with;
 * otherwise titles come from the parsed release names (name + year).
 */
public class ResultGrouper(
    private val ranker: ReleaseRanker = ReleaseRanker(),
    private val now: () -> Instant = Instant::now,
) {
    /**
     * The title picked; its releases without a language tag speak its [originalLanguage] (ISO 639-1, e.g. from TMDB), and
     * their bitrates are estimated over its [runtimeMinutes] (a film's, or an episode's of a show).
     */
    public data class KnownTitle(val name: String, val year: Int?, val isTv: Boolean, val originalLanguage: String? = null, val runtimeMinutes: Int? = null)

    /**
     * [releases] as titles, groups and ranked releases; a release more than one indexer listed counts once ([oneOfEach], by
     * [indexerOrder]: the indexers' ids in the user's order).
     */
    public fun group(releases: List<Release>, context: RankingContext, title: KnownTitle? = null, indexerOrder: List<String> = emptyList()): GroupedResults {
        val ranking = context.copy(
            originalLanguage = title?.originalLanguage ?: context.originalLanguage,
            runtimeMinutes = title?.runtimeMinutes ?: context.runtimeMinutes,
        )
        val unique = releases.oneOfEach(indexerOrder)
        val parsed = unique.associateWith { ReleaseParser.parse(it.item.title) }
        val movie = title?.takeUnless { it.isTv }

        val sections = unique.groupBy { release ->
            if (title != null) "known" else sectionKey(parsed.getValue(release))
        }.map { (sectionKey, sectionReleases) ->
            val first = parsed.getValue(sectionReleases.first())
            val groups = sectionReleases.groupBy { groupKey(parsed.getValue(it), movie) }
                .map { (groupKey, groupReleases) -> buildGroup("$sectionKey|$groupKey", kindOf(groupKey), groupReleases, parsed, ranking, movie) }
                .sortedWith(groupOrder)
            ResultSection(
                key = sectionKey,
                title = title?.name ?: first.title.ifBlank { sectionReleases.first().item.title },
                year = title?.year ?: sectionReleases.firstNotNullOfOrNull { parsed.getValue(it).year },
                groups = groups,
            )
        }
        // Titles with something playable first, then by their best release.
        return GroupedResults(
            sections.sortedWith(
                compareByDescending<ResultSection> { s -> s.groups.any { it.best != null } }
                    .thenByDescending { s -> s.groups.mapNotNull { it.best?.ranked?.score }.maxOrNull() ?: Double.MIN_VALUE },
            ),
        )
    }

    private fun sectionKey(p: ParsedRelease) = p.normalizedTitle + (p.year?.let { " ($it)" } ?: "")

    private fun groupKey(p: ParsedRelease, movie: KnownTitle?): String = when {
        p.airDate != null -> "date:${p.airDate}"
        p.isEpisode -> "ep:${p.seasons.firstOrNull()}:${p.episodes.joinToString(",")}"
        p.isSeasonPack -> "pack:${p.seasons.joinToString(",")}"
        // TitleMatcher accepts the neighbouring year, and some releases name none: all are the picked movie.
        movie != null -> "movie:known"
        p.year != null -> "movie:${p.year}"
        else -> "other:${p.normalizedTitle}:${p.raw}"
    }

    private fun kindOf(groupKey: String): GroupKind = when {
        groupKey.startsWith("date:") || groupKey.startsWith("ep:") -> GroupKind.Episode
        groupKey.startsWith("pack:") -> GroupKind.SeasonPack
        groupKey.startsWith("movie:") -> GroupKind.Movie
        else -> GroupKind.Other
    }

    private fun buildGroup(key: String, kind: GroupKind, releases: List<Release>, parsed: Map<Release, ParsedRelease>, context: RankingContext, movie: KnownTitle?): ResultGroup {
        val p = parsed.getValue(releases.first())
        val byId = releases.associateBy { it.key }
        val ranked = ranker.rank(releases.map { it.facts(now()) }, context).map { RankedItem(byId.getValue(it.facts.id), it) }
        return ResultGroup(
            key = key, kind = kind, season = p.seasons.firstOrNull(), episodes = p.episodes, airDate = p.airDate,
            year = if (kind == GroupKind.Movie && movie != null) movie.year else p.year,
            playable = ranked.filter { it.ranked.playable }, hidden = ranked.filterNot { it.ranked.playable },
        )
    }

    /** Newest episode first; a season's pack after its episodes; movies and others by best score. */
    private val groupOrder = compareByDescending<ResultGroup> { it.season ?: -1 }
        .thenBy { it.kind == GroupKind.SeasonPack }
        .thenByDescending { it.airDate ?: LocalDate.MIN }
        .thenByDescending { it.episodes.lastOrNull() ?: -1 }
        .thenByDescending { it.best?.ranked?.score ?: Double.MIN_VALUE }
}

/**
 * One of each release ([Release.key]), in the order each first came. A torrent listed by several indexers is one release,
 * as its service caches it: the listing kept is that of the first indexer in [indexerOrder] (the user's order, the
 * indexers' ids) that is used with a debrid service (its copy isn't [Availability.NotForService]), so it plays where it
 * can; with none, the first in that order. An indexer not in [indexerOrder] comes after those that are, in the order they
 * came. Each copy is checked on its own, and two can settle apart (one's check ran past its bound, the other's said
 * cached): a copy a service said it has goes before the rest, so the release still plays.
 */
public fun List<Release>.oneOfEach(indexerOrder: List<String> = emptyList()): List<Release> {
    val place = indexerOrder.withIndex().associate { (i, id) -> id to i }
    return groupBy { it.key }.values.map { copies ->
        copies.withIndex().minWith(
            compareBy<IndexedValue<Release>> { it.value.availability == Availability.NotForService }
                .thenBy { it.value.availability !is Availability.Cached }
                .thenBy { place[it.value.item.indexerId] ?: Int.MAX_VALUE }
                .thenBy { it.index },
        ).value
    }
}
