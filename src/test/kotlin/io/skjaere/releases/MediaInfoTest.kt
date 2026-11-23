package io.skjaere.releases

import io.skjaere.newznab.NewznabItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaInfoTest {

    @Test
    fun `newznab attribute values map to codecs, resolution and languages`() {
        val info = MediaInfo.fromNewznab(
            video = "AVC",
            audio = listOf("E-AC-3, AAC LC", "DTS-HD Master Audio"),
            resolution = "1920x800",
            languages = listOf("German, English"),
            subs = listOf("English / ger"),
        )
        assertEquals(VideoCodec.H264, info.videoCodec)
        assertEquals(1080, info.resolution)
        assertEquals(listOf(AudioCodec.Eac3, AudioCodec.Aac, AudioCodec.DtsHdMa), info.audio)
        assertEquals(setOf("de", "en"), info.languages)
        assertEquals(setOf("en", "de"), info.subtitles)
    }

    @Test
    fun `an item's attributes are its media info, and an item without any has none`() {
        fun item(attributes: Map<String, List<String>>) = NewznabItem(
            indexerId = "a", guid = "g", title = "Harbor.Lights.S01E02.1080p.WEB-DL.DDP5.1.H.264-GRP", sizeBytes = 2_000_000_000,
            publishedAt = null, postedAt = null, categories = emptyList(), grabs = null, passworded = false, files = null,
            tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null, season = null, episode = null, coverUrl = null,
            downloadTemplate = null, attributes = attributes,
        )
        val info = MediaInfo.of(
            item(
                mapOf(
                    "video" to listOf("AVC"),
                    "audio" to listOf("E-AC-3", "AAC LC"),
                    "resolution" to listOf("1920x800"),
                    "language" to listOf("German, English"),
                    "subs" to listOf("English / ger"),
                ),
            ),
        )
        assertEquals(MediaInfo(VideoCodec.H264, 1080, listOf(AudioCodec.Eac3, AudioCodec.Aac), setOf("de", "en"), setOf("en", "de")), info)
        assertNull(MediaInfo.of(item(emptyMap())))
        assertNull(MediaInfo.of(item(mapOf("audio" to listOf("an unknown codec")))), "nothing it can read")
        // As NZStreamer's indexer parser had it: subtitles alone say nothing of what plays.
        assertNull(MediaInfo.of(item(mapOf("subs" to listOf("English")))), "subtitles alone")
    }

    @Test
    fun `resolution classes`() {
        assertEquals(2160, MediaInfo.resolutionClass("3840x1600"))
        assertEquals(720, MediaInfo.resolutionClass("1280x536"))
        assertEquals(576, MediaInfo.resolutionClass("720x576"))
        assertEquals(1080, MediaInfo.resolutionClass("1080p"))
    }

    @Test
    fun `an extra AAC track reported by the indexer makes a DD+ release playable`() {
        val phone = StaticDeviceCapabilities(audio = setOf(AudioCodec.Aac))
        val name = "Harbor.Lights.S01E02.1080p.WEB-DL.DDP5.1.H.264-GRP"
        val byName = ReleaseFacts(id = "a", name = name, sizeBytes = 2_000_000_000)
        val withInfo = byName.copy(mediaInfo = MediaInfo(audio = listOf(AudioCodec.Eac3, AudioCodec.Aac)))

        val ranker = ReleaseRanker()
        assertFalse(ranker.rank(listOf(byName), RankingContext(phone)).single().playable)
        assertTrue(ranker.rank(listOf(withInfo), RankingContext(phone)).single().playable)
    }

    @Test
    fun `media info languages replace the name's dual-language claim`() {
        val parsed = ReleaseParser.parse("Harbor.Lights.S01E02.German.DL.1080p.WEB.x264-GRP")
            .withMediaInfo(MediaInfo(languages = setOf("de")))
        assertEquals(setOf("de"), parsed.languages)
        assertFalse(parsed.dualLanguage)
    }
}
