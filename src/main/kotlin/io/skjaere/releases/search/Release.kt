package io.skjaere.releases.search

import io.skjaere.newznab.NewznabItem
import io.skjaere.releases.Availability
import io.skjaere.releases.MediaInfo
import io.skjaere.releases.ReleaseFacts
import io.skjaere.releases.ReleaseKey
import java.time.Duration
import java.time.Instant

/**
 * A search result: the indexer's [item], and for a torrent its cache check's answer ([availability]), null while not
 * known and for an NZB.
 */
public data class Release(val item: NewznabItem, val availability: Availability? = null) {
    /** What the release is known by ([ReleaseKey]): a torrent by its info hash, any other by its indexer and guid. */
    public val key: String = ReleaseKey.of(item.indexerId, item.guid, item.infoHash)
}

/**
 * [this] as the ranker reads it: what the item's attributes report of its files ([MediaInfo.of]), its [availability],
 * a torrent by its info hash, and its age at [now].
 */
internal fun Release.facts(now: Instant): ReleaseFacts = ReleaseFacts(
    id = key,
    name = item.title,
    sizeBytes = item.sizeBytes,
    passworded = item.passworded,
    grabs = item.grabs,
    ageDays = item.postedAt?.let { Duration.between(it, now).toDays().toInt() },
    mediaInfo = MediaInfo.of(item),
    source = if (item.infoHash != null) ReleaseFacts.TORRENT else ReleaseFacts.USENET,
    availability = availability,
)
