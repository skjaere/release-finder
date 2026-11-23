package io.skjaere.releases.search

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.skjaere.newznab.Indexer
import io.skjaere.newznab.NewznabError
import io.skjaere.newznab.Newznab
import io.skjaere.newznab.SearchQuery
import io.skjaere.newznab.SearchType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Searching through newznab-kotlin: each indexer's outcome as a [SearchOutcome], its items as [Release]s. */
class IndexerSearchTest {
    private val good = Indexer("good", "https://good.example/api", "k", "Good")
    private val noTv = Indexer("text", "https://text.example/api", "k", "Text only")
    private val badKey = Indexer("bad", "https://bad.example/api", "k", "Bad key")
    private val off = Indexer("off", "https://off.example/api", "k", "Off", enabled = false)

    private val requests: MutableList<HttpRequestData> = CopyOnWriteArrayList()

    private val newznab = Newznab {
        httpClient = HttpClient(MockEngine { request ->
            requests += request
            val body = when {
                request.url.host == "bad.example" -> """<error code="100" description="Incorrect user credentials"/>"""
                request.url.parameters["t"] == "caps" && request.url.host == "text.example" ->
                    """<caps><searching><search available="yes" supportedParams="q"/></searching></caps>"""
                request.url.parameters["t"] == "caps" ->
                    """<caps><searching><search available="yes" supportedParams="q"/><tv-search available="yes" supportedParams="q,tvdbid,season,ep"/></searching></caps>"""
                else -> """
                    <rss xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>
                      <item><title>Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GRP</title><guid>abc123</guid>
                        <newznab:attr name="size" value="2000000000"/><newznab:attr name="audio" value="AAC"/></item>
                    </channel></rss>
                """.trimIndent()
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/xml"))
        }) {
            install(HttpTimeout)
            expectSuccess = false
        }
    }

    private val tvQuery = SearchQuery.Tv(query = "Harbor Lights", season = 1, episode = 2)

    /** A TV search where the indexer's caps have one; else none, which skips it. */
    private fun search(fresh: Boolean = false) = runBlocking {
        IndexerSearch(newznab).search(listOf(good, noTv, badKey, off), fresh) { caps -> tvQuery.takeIf { caps.supports(SearchType.Tv) } }
            .toList().associateBy { it.indexer.id }
    }

    @Test
    fun `each enabled indexer gives its results as releases, its failure, or its skip`() {
        val outcomes = search()
        assertEquals(setOf("good", "text", "bad"), outcomes.keys, "a disabled indexer isn't searched")

        val results = assertIs<SearchOutcome.Results>(outcomes.getValue("good"))
        assertEquals(tvQuery, results.query)
        assertEquals(results.page.items.map { Release(it) }, results.releases)
        val release = results.releases.single()
        assertEquals("Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GRP", release.item.title)
        assertEquals("good/abc123", release.key)
        assertNull(release.availability, "nothing has checked it")

        assertIs<NewznabError.Auth>(assertIs<SearchOutcome.Failed>(outcomes.getValue("bad")).error)
        assertIs<SearchOutcome.Skipped>(outcomes.getValue("text"))
    }

    @Test
    fun `an answer is reused unless the search is fresh`() {
        fun searches() = requests.count { it.url.parameters["t"] == "tvsearch" }
        search()
        search()
        assertEquals(1, searches())
        search(fresh = true)
        assertEquals(2, searches())
    }
}
