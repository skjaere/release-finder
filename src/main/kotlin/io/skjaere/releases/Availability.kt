package io.skjaere.releases

/**
 * Whether a torrent can play from the user's debrid service, as its cache check settled it. The words shown for it name
 * no service. An NZB has none: it plays from Usenet.
 */
public sealed interface Availability {
    /** The service accounts that said they have it cached, by their ids, in the order they are tried (the user's order). */
    public data class Cached(val accounts: List<String>) : Availability

    /** Every account asked said it isn't cached: hidden, never tried. */
    public data object NotCached : Availability

    /** No account said yes, and one couldn't be asked or the check ran out of time: hidden, never tried. */
    public data object NotChecked : Availability

    /** From a torrent indexer the user doesn't use with a debrid service: nothing of it went to any service. */
    public data object NotForService : Availability
}
