package io.skjaere.releases.search

import io.skjaere.newznab.NewznabItem
import io.skjaere.releases.AudioCodec
import io.skjaere.releases.Availability
import io.skjaere.releases.MediaInfo
import io.skjaere.releases.RankingContext
import io.skjaere.releases.ReleaseFacts
import io.skjaere.releases.StaticDeviceCapabilities
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/** A search result as the ranker reads it: the item's own facts, its media info, its availability and its source. */
class ReleaseTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")

    private fun item(title: String, attributes: Map<String, List<String>> = emptyMap(), infoHash: String? = null) = NewznabItem(
        indexerId = "a", guid = infoHash?.let { "btih:$it" } ?: "g1", title = title, sizeBytes = 2_000_000_000,
        publishedAt = null, postedAt = now.minus(Duration.ofDays(3)), categories = emptyList(), grabs = 7, passworded = true,
        files = null, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null,
        downloadTemplate = null, attributes = attributes, infoHash = infoHash,
    )

    @Test
    fun `a release's key is its torrent's hash, else its indexer and guid`() {
        val hash = "0123456789ABCDEF0123456789ABCDEF01234567"
        assertEquals("a/g1", Release(item("Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GRP")).key)
        assertEquals("btih:${hash.lowercase()}", Release(item("Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GRP", infoHash = hash)).key)
    }

    @Test
    fun `an NZB's facts are its item's, its media info from its attributes`() {
        val release = Release(item("Harbor.Lights.S01E02.1080p.WEB-DL.DDP5.1.H.264-GRP", mapOf("audio" to listOf("E-AC-3", "AAC"))))
        assertEquals(
            ReleaseFacts(
                id = "a/g1", name = release.item.title, sizeBytes = 2_000_000_000, passworded = true, grabs = 7, ageDays = 3,
                mediaInfo = MediaInfo(audio = listOf(AudioCodec.Eac3, AudioCodec.Aac)), source = ReleaseFacts.USENET, availability = null,
            ),
            release.facts(now),
        )
    }

    @Test
    fun `a torrent's facts carry its availability and its source`() {
        val cached = Availability.Cached(listOf("torbox-1"))
        val facts = Release(item("Harbor.Lights.S01E02.1080p.WEB-DL.H.264-GRP", infoHash = "1".repeat(40)), cached).facts(now)
        assertEquals(ReleaseFacts.TORRENT to cached, facts.source to facts.availability)
        assertEquals("btih:${"1".repeat(40)}", facts.id)
    }

    /** The indexer's MediaInfo makes the ranking: a DD+ release with an AAC track plays where only AAC decodes. */
    @Test
    fun `the grouper ranks a release by what its item's attributes report`() {
        val aacOnly = RankingContext(StaticDeviceCapabilities(audio = setOf(AudioCodec.Aac)))
        val name = "Harbor.Lights.S01E02.1080p.WEB-DL.DDP5.1.H.264-GRP"
        fun playable(attributes: Map<String, List<String>>) =
            ResultGrouper().group(listOf(Release(item(name, attributes))), aacOnly).sections.single().groups.single().playable.size
        assertEquals(0, playable(emptyMap()))
        assertEquals(1, playable(mapOf("audio" to listOf("E-AC-3, AAC"))))
    }
}
