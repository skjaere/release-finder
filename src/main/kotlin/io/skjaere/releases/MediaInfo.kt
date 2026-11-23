package io.skjaere.releases

import io.skjaere.newznab.NewznabItem
import java.util.Locale

/**
 * What an indexer's MediaInfo scan says about a release's files. When present it is more
 * reliable than the release name, which usually mentions one audio track and often none.
 */
public data class MediaInfo(
    val videoCodec: VideoCodec? = null,
    val resolution: Int? = null,
    val audio: List<AudioCodec> = emptyList(),
    /** ISO 639-1 codes of the audio tracks. */
    val languages: Set<String> = emptySet(),
    val subtitles: Set<String> = emptySet(),
) {
    /** Nothing the ranking reads: no video codec, resolution, audio or audio language. */
    public val isEmpty: Boolean get() = videoCodec == null && resolution == null && audio.isEmpty() && languages.isEmpty()

    public companion object {
        /**
         * What [item]'s `video`, `audio`, `resolution`, `language` and `subs` attributes say; null when they say
         * nothing of the video, audio or languages ([isEmpty]).
         */
        public fun of(item: NewznabItem): MediaInfo? =
            fromNewznab(item.video, item.audio, item.resolution, item.languages, item.subtitles).takeUnless { it.isEmpty }

        /** Builds from Newznab `video`, `audio`, `resolution`, `language` and `subs` attribute values. */
        public fun fromNewznab(video: String?, audio: List<String>, resolution: String?, languages: List<String>, subs: List<String>): MediaInfo =
            MediaInfo(
                videoCodec = video?.let(::videoCodecNamed),
                resolution = resolution?.let(::resolutionClass),
                audio = audio.flatMap(::splitList).mapNotNull(::audioCodecNamed).distinct(),
                languages = languages.flatMap(::splitList).mapNotNull(::languageCode).toSet(),
                subtitles = subs.flatMap(::splitList).mapNotNull(::languageCode).toSet(),
            )

        /** ISO 639-2/B codes that MediaInfo also emits (Java only knows the /T ones). */
        private val BIBLIOGRAPHIC_CODES = mapOf("ger" to "de", "fre" to "fr", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el")

        private fun splitList(value: String) = value.split(',', '/', '|', ';').map { it.trim() }.filter { it.isNotEmpty() }

        private fun key(name: String) = name.uppercase().replace(Regex("[^A-Z0-9+]"), "")

        /** The audio codec a MediaInfo or indexer name means ("E-AC-3", "DTS-HD Master Audio", "AAC LC"); null when unknown. */
        public fun audioCodecNamed(name: String): AudioCodec? = key(name).let { k ->
            when {
                k.startsWith("EAC3") || k.startsWith("DD+") || k.startsWith("DDP") || k.contains("DOLBYDIGITALPLUS") -> AudioCodec.Eac3
                k.startsWith("AC3") || k == "DD" || k.startsWith("DOLBYDIGITAL") -> AudioCodec.Ac3
                k.contains("TRUEHD") || k.startsWith("MLP") -> AudioCodec.TrueHd
                k.startsWith("DTSHDMA") || k.contains("MASTERAUDIO") || k.contains("XLL") -> AudioCodec.DtsHdMa
                k.startsWith("DTSX") -> AudioCodec.DtsX
                k.startsWith("DTSHD") -> AudioCodec.DtsHd
                k.startsWith("DTS") -> AudioCodec.Dts
                k.contains("AAC") -> AudioCodec.Aac
                k.startsWith("FLAC") -> AudioCodec.Flac
                k.startsWith("OPUS") -> AudioCodec.Opus
                k.startsWith("MP3") || k.startsWith("MPEGAUDIO") -> AudioCodec.Mp3
                k.contains("PCM") -> AudioCodec.Pcm
                else -> null
            }
        }

        /** The video codec a MediaInfo or indexer name means ("AVC", "x265", "MPEG-4 Visual"); null when unknown. */
        public fun videoCodecNamed(name: String): VideoCodec? = key(name).let { k ->
            when {
                k.startsWith("HEVC") || k.contains("265") -> VideoCodec.Hevc
                k.startsWith("AVC") || k.contains("264") -> VideoCodec.H264
                k.startsWith("AV1") -> VideoCodec.Av1
                k.startsWith("VP9") -> VideoCodec.Vp9
                k.contains("XVID") || k.contains("DIVX") || k.startsWith("MPEG4VISUAL") -> VideoCodec.Xvid
                k.startsWith("VC1") -> VideoCodec.Vc1
                k.startsWith("MPEGVIDEO") || k.startsWith("MPEG2") -> VideoCodec.Mpeg2
                else -> null
            }
        }

        /**
         * The resolution class ("1080" for 1080p) of a size ("1920x800") or a height ("1080p"). "1920x800" is a 1080p
         * release cropped to 2.40:1, so a size is classified by its width first.
         */
        public fun resolutionClass(value: String): Int? {
            Regex("(\\d{3,4})\\s*[xX×]\\s*(\\d{3,4})").find(value)?.let { m ->
                val (width, height) = m.destructured.toList().map { it.toInt() }
                return when {
                    width >= 3200 || height >= 1800 -> 2160
                    width >= 1800 || height >= 1000 -> 1080
                    width >= 1200 || height >= 700 -> 720
                    else -> height
                }
            }
            return Regex("(\\d{3,4})[pi]?", RegexOption.IGNORE_CASE).matchEntire(value.trim())?.groupValues?.get(1)?.toInt()
        }

        private val languageNames: Map<String, String> = Locale.getISOLanguages()
            .flatMap { code ->
                val locale = Locale.forLanguageTag(code)
                listOfNotNull(
                    locale.getDisplayLanguage(Locale.ENGLISH), locale.getDisplayLanguage(locale), code,
                    runCatching { locale.isO3Language }.getOrNull(),
                )
                    .filter { it.isNotEmpty() }
                    .map { it.lowercase() to code }
            }
            .toMap() + BIBLIOGRAPHIC_CODES

        /** The ISO 639-1 code of a language's name, in English or its own ("Deutsch"), or its 639-2 code; null when unknown. */
        public fun languageCode(name: String): String? =
            languageNames[name.trim().lowercase().substringBefore(" (").substringBefore(" - ")]
    }
}

/** The release with what MediaInfo reported in place of what the name claimed. */
public fun ParsedRelease.withMediaInfo(info: MediaInfo?): ParsedRelease =
    if (info == null || info.isEmpty) this else copy(
        videoCodec = info.videoCodec ?: videoCodec,
        resolution = info.resolution ?: resolution,
        audio = info.audio.ifEmpty { audio },
        languages = info.languages.ifEmpty { languages },
        dualLanguage = dualLanguage && info.languages.isEmpty(),
    )
