package io.skjaere.releases.search

import io.skjaere.newznab.Indexer
import io.skjaere.newznab.IndexerCaps
import io.skjaere.newznab.IndexerKind
import io.skjaere.newznab.NewznabError
import io.skjaere.newznab.NewznabItem
import io.skjaere.newznab.SearchPage
import io.skjaere.newznab.SearchQuery
import io.skjaere.newznab.SearchType
import io.skjaere.newznab.TvIds
import io.skjaere.releases.Availability
import io.skjaere.releases.RankingContext
import io.skjaere.releases.RankingPrefs
import io.skjaere.releases.ReleaseReason
import io.skjaere.releases.StaticDeviceCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A movie's search: by id where an indexer can, the name checked where it can't, its releases ranked as one group. */
@OptIn(ExperimentalCoroutinesApi::class)
class MovieReleaseFinderTest {
    private val copperCrown = MovieRequest("Copper Crown", 2019, imdb = "tt9000012", tmdb = 900011, runtimeMinutes = 118)

    /** No 10-bit decoding: a 10-bit HEVC release is hidden. */
    private val context = RankingContext(StaticDeviceCapabilities(videoTenBit = emptySet()))
    private val a = Indexer("a", "http://a.invalid", "key-a", "Indexer A")
    private val b = Indexer("b", "http://b.invalid", "key-b", "Indexer B")
    private val c = Indexer("c", "http://c.invalid", "key-c", "Indexer C")
    private val d = Indexer("d", "http://d.invalid", "key-d", "Indexer D")

    private fun item(indexer: Indexer, title: String, size: Long = 8_000_000_000) = Release(
        NewznabItem(
            indexerId = indexer.id, guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, downloadTemplate = null, attributes = emptyMap(),
        ),
    )

    private fun resultsOf(indexer: Indexer, query: SearchQuery, vararg releases: Release) =
        SearchOutcome.Results(indexer, query, SearchPage(releases.map { it.item }, offset = 0, total = null, limits = null), releases.toList())

    private fun results(indexer: Indexer, query: SearchQuery, vararg titles: String) =
        resultsOf(indexer, query, *titles.map { item(indexer, it) }.toTypedArray())

    /** A search whose indexers answer [outcomes], in this order, whatever they are asked. */
    private class Answering(private val outcomes: List<SearchOutcome>) : ReleaseSearch {
        override fun search(indexers: List<Indexer>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<SearchOutcome> = outcomes.asFlow()
    }

    /** What [request]'s search finds once every indexer of [outcomes] has given them: its last result. */
    private fun find(request: MovieRequest, outcomes: List<SearchOutcome>, context: RankingContext = this.context): MovieFindResult = runBlocking {
        MovieReleaseFinder(Answering(outcomes)).find(request, outcomes.map { it.indexer }.distinct(), context).last()
    }

    private val MovieFindResult.releases get() = playable + hidden

    @Test
    fun `the movie's releases from every indexer are one group, those found by name only when they are the movie`() {
        val find = find(
            copperCrown,
            listOf(
                // By its IMDb id: whatever the indexer calls them, they are the movie.
                results(a, SearchQuery.Movie(imdb = "tt9000012"), "Copper.Crown.2019.1080p.BluRay.x264-GOOD", "CC.2019.720p.WEB.h264-SHORT"),
                // By name: only the ones named for it.
                results(b, SearchQuery.Text("Copper Crown 2019"), "Copper.Crown.2019.1080p.WEB-DL.H.264-NAMED", "Brass.Crown.2019.1080p.WEB-DL.H.264-OTHER", "CC.2019.720p.WEB.h264-SHORT"),
            ),
        )
        // One group: every release taken is in the one ranked list.
        assertEquals(3, find.releases.size)
        assertEquals(
            setOf("a/Copper.Crown.2019.1080p.BluRay.x264-GOOD", "a/CC.2019.720p.WEB.h264-SHORT", "b/Copper.Crown.2019.1080p.WEB-DL.H.264-NAMED"),
            find.playable.map { it.key }.toSet(),
        )
        assertEquals(listOf(Triple("IMDb id", 2, 2), Triple("name", 3, 1)), find.indexers.map { Triple(it.by, it.found, it.accepted) })
    }

    @Test
    fun `the movie's untagged releases speak its original language`() {
        val outcomes = listOf(results(a, SearchQuery.Movie(imdb = "tt9000042"), "Moonwell.Village.2021.1080p.BluRay.x264-KO", "Moonwell.Village.2021.ENGLISH.720p.BluRay.x264-DUB"))
        val moonwell = MovieRequest("Moonwell Village", 2021, imdb = "tt9000042", tmdb = 900042, originalLanguage = "ko", runtimeMinutes = 118)
        // English is preferred: the dub beats the untagged release of a Korean film, which an unknown original language takes for English.
        assertEquals("a/Moonwell.Village.2021.ENGLISH.720p.BluRay.x264-DUB", find(moonwell, outcomes).playable.first().key)
        assertEquals("a/Moonwell.Village.2021.1080p.BluRay.x264-KO", find(moonwell.copy(originalLanguage = null), outcomes).playable.first().key)
    }

    /**
     * With a maximum bitrate, a movie's releases are estimated over its runtime: a 38 GB remux of a 165-minute film is
     * about 31 Mbps and fits 40 Mbps, so it goes first; one of 30 GB of an 85-minute film is about 47 Mbps and doesn't.
     */
    @Test
    fun `a movie's releases are estimated over its runtime from TMDB`() {
        val upTo40 = context.copy(prefs = RankingPrefs(maxBitrate = 40_000_000))
        fun found(runtime: Int, remux: Long) = find(
            copperCrown.copy(runtimeMinutes = runtime),
            listOf(
                resultsOf(
                    a,
                    SearchQuery.Movie(imdb = "tt9000012"),
                    item(a, "Copper.Crown.2019.1080p.BluRay.REMUX.AVC-GRP", size = remux),
                    item(a, "Copper.Crown.2019.1080p.WEB-DL.H.264-GRP", size = 10_000_000_000),
                ),
            ),
            upTo40,
        ).playable
        val long = found(runtime = 165, remux = 38_000_000_000)
        assertEquals("a/Copper.Crown.2019.1080p.BluRay.REMUX.AVC-GRP", long.first().key)
        assertEquals(emptyList(), long.flatMap { it.ranked.reasons }.filterIsInstance<ReleaseReason.AboveMaxBitrate>())
        val short = found(runtime = 85, remux = 30_000_000_000)
        assertEquals("a/Copper.Crown.2019.1080p.WEB-DL.H.264-GRP", short.first().key)
        assertEquals(listOf(ReleaseReason.AboveMaxBitrate(40)), short.last().ranked.reasons.filterIsInstance<ReleaseReason.AboveMaxBitrate>())
    }

    /** What the movie's search takes of [outcome]: the titles of its releases, and how many its indexer's report says. */
    private fun taken(outcome: SearchOutcome): Pair<Set<String>, Int> =
        find(copperCrown, listOf(outcome)).let { found -> found.releases.map { it.release.item.title }.toSet() to found.indexers.single().accepted }

    /** However an indexer was asked by name, only the movie's releases are taken; asked by an id, all of them. */
    @Test
    fun `an indexer answers alike on Search and on the movie page`() {
        val byName = listOf(SearchQuery.Text("Copper Crown 2019"), SearchQuery.Movie(query = "Copper Crown"), SearchQuery.Tv(query = "Copper Crown"))
        val byId = listOf(SearchQuery.Movie(imdb = "tt9000012"), SearchQuery.Movie(tmdb = 900011), SearchQuery.Tv(ids = TvIds(tvdb = 900001)))
        val titles = arrayOf("Copper.Crown.2019.1080p.WEB-DL.H.264-NAMED", "Brass.Crown.2019.1080p.WEB-DL.H.264-OTHER")
        byName.forEach { q -> assertEquals(setOf(titles[0]) to 1, taken(results(a, q, *titles)), "$q") }
        byId.forEach { q -> assertEquals(titles.toSet() to 2, taken(results(a, q, *titles)), "$q") }
    }

    @Test
    fun `nothing playable leaves no candidates, the hidden ones kept, and each indexer's line says what it did`() {
        val timedOut = NewznabError.Timeout("Timed out")
        val find = find(
            copperCrown,
            listOf(
                results(a, SearchQuery.Movie(imdb = "tt9000012")),
                results(b, SearchQuery.Movie(imdb = "tt9000012"), "Copper.Crown.2019.2160p.WEB.H265.10bit.HDR.DDP5.1-TENBIT"),
                SearchOutcome.Failed(c, timedOut),
                SearchOutcome.Skipped(d, "Doesn't support this search"),
            ),
        )
        assertEquals(emptyList(), find.playable)
        assertEquals(listOf("b/Copper.Crown.2019.2160p.WEB.H265.10bit.HDR.DDP5.1-TENBIT"), find.hidden.map { it.key })
        // Each indexer's report: no results, one found and taken (none playable), its error, and why it was skipped.
        assertEquals(
            listOf(
                IndexerReport(a, "IMDb id", found = 0, accepted = 0),
                IndexerReport(b, "IMDb id", found = 1, accepted = 1),
                IndexerReport(c, null, error = timedOut),
                IndexerReport(d, null, skipped = "Doesn't support this search"),
            ),
            find.indexers,
        )
        // Four indexers searched, one release found.
        assertEquals(4 to 1, find.indexers.size to find.releases.size)
    }

    @Test
    fun `no indexer answering anything gives no group`() {
        val find = find(copperCrown, listOf(results(a, SearchQuery.Movie(imdb = "tt9000012"))))
        assertEquals(emptyList(), find.releases)
        // One indexer searched, nothing found.
        assertEquals(listOf(0 to 0), find.indexers.map { it.found to it.accepted })
    }

    /**
     * A torrent indexer whose releases none of the services has cached: its report counts them as found, and each is
     * hidden as not cached.
     */
    @Test
    fun `a torrent indexer whose releases none of the services has cached says so on the no-release sheet`() {
        val prowlarr = Indexer("p", "http://p.invalid", "key-p", "Home Prowlarr", kind = IndexerKind.Torznab)
        val byId = SearchQuery.Movie(imdb = "tt9000012")
        val torrents = listOf("Copper.Crown.2019.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.5.1-DRIFT", "Copper.Crown.2019.720p.HDTV.x264-DRIFT").mapIndexed { i, title ->
            Release(item(prowlarr, title).item.copy(guid = "btih:${"%040x".format(i + 1)}", infoHash = "%040x".format(i + 1)))
        }
        val find = find(
            copperCrown,
            listOf(
                results(a, byId),
                SearchOutcome.Checking(prowlarr, byId, torrents, waiting = 2),
                SearchOutcome.Checked(prowlarr, torrents.map { it.copy(availability = Availability.NotCached) }, waiting = 0),
            ),
        )
        assertEquals(listOf(IndexerReport(a, "IMDb id", 0, 0), IndexerReport(prowlarr, "IMDb id", found = 2, accepted = 2)), find.indexers)
        assertEquals(emptyList(), find.playable)
        assertEquals(torrents.map { it.key }.toSet(), find.hidden.map { it.key }.toSet())
        assertEquals(listOf(Availability.NotCached, Availability.NotCached), find.hidden.map { it.release.availability })
        assertEquals(listOf(true, true), find.hidden.map { ReleaseReason.NotCached in it.ranked.reasons })
    }

    // The finder's own planning, and its results over time.

    @Test
    fun `each indexer is asked by the movie's id where its caps allow, else by name`() {
        val asked = mutableListOf<SearchQuery?>()
        val caps = listOf(
            IndexerCaps(searching = mapOf(SearchType.Movie to setOf("q", "imdbid", "tmdbid"))),
            IndexerCaps(searching = mapOf(SearchType.Movie to setOf("q", "tmdbid"))),
            IndexerCaps(searching = mapOf(SearchType.Movie to setOf("q"))),
            IndexerCaps(searching = mapOf(SearchType.Search to setOf("q"))),
        )
        val search = ReleaseSearch { _, _, plan -> flow<SearchOutcome> { caps.forEach { asked += plan(it) } } }
        runBlocking { MovieReleaseFinder(search).find(copperCrown, listOf(a), context).last() }
        assertEquals(
            listOf<SearchQuery?>(
                SearchQuery.Movie(imdb = "tt9000012"),
                SearchQuery.Movie(tmdb = 900011),
                SearchQuery.Movie(query = "Copper Crown"),
                SearchQuery.Text("Copper Crown 2019"),
            ),
            asked,
        )
    }

    /** Once every indexer has answered, a torrent indexer at its Checking; then again as its torrents join, the last whole. */
    @Test
    fun `the result comes once every indexer has answered, then again as checked torrents join`() = runTest {
        val prowlarr = Indexer("p", "http://p.invalid", "key-p", "Home Prowlarr", kind = IndexerKind.Torznab)
        val byId = SearchQuery.Movie(imdb = "tt9000012")
        val torrent = Release(item(prowlarr, "Copper.Crown.2019.1080p.WEB-DL.H.264-DRIFT").item.copy(guid = "btih:${"1".repeat(40)}", infoHash = "1".repeat(40)))
        val indexers = ReleaseSearch { _, _, _ ->
            channelFlow {
                launch { delay(100); send(results(a, byId, "Copper.Crown.2019.1080p.BluRay.x264-GOOD")) }
                launch { delay(200); send(resultsOf(prowlarr, byId, torrent)) }
            }
        }
        val checked = CheckedSearch(indexers) { _, releases -> flow { delay(1_000); emit(releases.map { it.copy(availability = Availability.Cached(listOf("torbox-1"))) }) } }
        val finder = MovieReleaseFinder(checked, defaultDispatcher = StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<Pair<Long, List<String>>>()
        launch { finder.find(copperCrown, listOf(a, prowlarr), context).collect { seen += testScheduler.currentTime to it.playable.map { r -> r.key } } }
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf(
                200L to listOf("a/Copper.Crown.2019.1080p.BluRay.x264-GOOD"),
                1_200L to listOf("a/Copper.Crown.2019.1080p.BluRay.x264-GOOD", torrent.key),
            ),
            seen,
        )
    }
}
