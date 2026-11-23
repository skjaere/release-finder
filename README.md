# release-finder

[![CI](https://github.com/skjaere/release-finder/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/skjaere/release-finder/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/skjaere/release-finder/branch/main/graph/badge.svg)](https://codecov.io/gh/skjaere/release-finder)
[![](https://jitpack.io/v/skjaere/release-finder.svg)](https://jitpack.io/#skjaere/release-finder)

Finds the releases of an episode or a movie across Newznab indexers, and ranks them for the device that will play them.
Built on [newznab-kotlin](https://github.com/skjaere/newznab-kotlin).

- **Release-name parsing:** title, year, season and episodes, air date, resolution, source, video and audio codecs,
  HDR, languages, edition and group.
- **Ranking for a device:** releases are ranked against what the device can decode and show, the user's preferences
  (quality, audio languages, bitrate caps, retention) and the connection. Releases it can't play are hidden, each with
  the reasons.
- **Episode search in stages:**
  1. The episode, by the best id each indexer supports (TVDB, TMDB, IMDb, TVmaze), with the season packs searched at
     the same time.
  2. By air date, for daily shows.
  3. By name, when the id searches found nothing.

  Every result is checked against its name: the right episode, and the right show when the indexer matched by name.
  A whole season can be searched at once.
- **Movie search:** by IMDb or TMDB id where an indexer supports it, else by name and year.
- **Torrents (optional):** a `CheckedSearch` can check torrent results against a debrid service's cache as they arrive.

## Installation

Via [JitPack](https://jitpack.io):

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.skjaere:release-finder:v0.1.0")
    implementation("io.ktor:ktor-client-okhttp:3.5.2") // or any Ktor client engine
}
```

## Usage

```kotlin
val newznab = Newznab { userAgent = "my-app/1.0" }
val search = IndexerSearch(newznab)

// What the playing device can decode and show.
val context = RankingContext(
    device = StaticDeviceCapabilities(displayHeight = 2160),
    prefs = RankingPrefs(audioLanguages = listOf("en")),
)

// An episode: events as each stage and indexer answers, then the ranked result.
EpisodeReleaseFinder(search).find(
    EpisodeRequest(ShowRef("Harbor Lights", year = 2024, ids = TvIds(tvdb = 81189)), season = 1, episode = 2),
    indexers,
    context,
).collect { event ->
    when (event) {
        is FinderEvent.StageStarted -> showProgress(event.stage)
        is FinderEvent.IndexerAnswered -> showIndexer(event.report)
        is FinderEvent.Finished -> play(event.result.playable)   // best first
        is FinderEvent.Joined -> Unit                              // torrents checked after the end
    }
}

// A movie: the ranked releases, again each time more arrive.
MovieReleaseFinder(search).find(MovieRequest("The Long Harbor", year = 1998, imdb = "tt0133093"), indexers, context)
    .collect { result -> show(result.playable, result.hidden) }

// Parsing and ranking on their own.
val parsed = ReleaseParser.parse("Harbor.Lights.S01E02.2160p.WEB-DL.DDP5.1.HDR.HEVC-GRP")
val ranked = ReleaseRanker().rank(facts, context)
```

Each `FoundRelease` and `RankedItem` holds the `Release`, which wraps the indexer's `NewznabItem`. Download the NZB
with `newznab.download(indexer, release.item)`.

## Licence

[PolyForm Noncommercial 1.0.0](LICENSE.md).
