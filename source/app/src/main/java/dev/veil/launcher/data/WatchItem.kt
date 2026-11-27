package dev.veil.launcher.data

data class WatchItem(
    val isMovie: Boolean,
    val title: String,
    val traktId: Int?,
    val imdb: String?,
    val tmdb: Int?,
    val tvdb: Int? = null,
    /** The connected service's own id for the show/movie (Simkl's number, MDBList's code), for its writes. */
    val serviceId: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeTitle: String? = null,
    /** 0f..1f, 0 when not started (up next). */
    val progress: Float = 0f,
    /** Runtime in minutes, when Trakt knows it. */
    val runtime: Int? = null,
    val upNext: Boolean = false,
    /** The up-next episode aired after you last watched this show (within the last two weeks). */
    val isNew: Boolean = false,
    /** The service's id for the paused-progress entry (from /sync/playback), if any. */
    val playbackId: Long? = null,
    val sortTime: Long = 0L,
    /** Full-resolution backdrop for the hero (TMDB "original", decoded at screen size). */
    val backdrop: String? = null,
    /** Smaller backdrop for pill thumbnails. */
    val thumb: String? = null,
    val logo: String? = null,
    val poster: String? = null,
    val nativeIntentUri: String? = null,
    val nativePackage: String? = null,
    val isNativeWatchNext: Boolean = false,
    val isTrending: Boolean = false,
) {
    val key: String get() = if (isMovie) "m:${imdb ?: tmdb ?: title}" else "s:${traktId ?: serviceId ?: tmdb ?: title}"

    /** "S7 E13 · Funeral (1)" or "Movie". */
    val episodeLine: String
        get() = if (isMovie) "Movie" else buildString {
            append("S$season E$episode")
            episodeTitle?.let { append(" · ").append(it) }
        }

    /** Hero line: "Season 7 · Episode 13 — Funeral (1)". */
    val heroLine: String
        get() = when {
            isMovie -> "Movie"
            isTrending -> "TV show"
            else -> buildString {
                append("Season $season · Episode $episode")
                episodeTitle?.let { append(" — ").append(it) }
            }
        }

    /** Minutes still to watch (whole episode for up-next), or null if the runtime is unknown. */
    val minutesLeft: Int?
        get() = runtime?.takeIf { it > 0 }?.let { (it * (1f - progress)).toInt().coerceAtLeast(1) }

    /** "18 min left", "1 h 12 min left", or "42% watched" when the runtime is unknown. */
    val remainingLine: String
        get() {
            val rt = runtime
            if (rt == null || rt <= 0) return "${(progress * 100).toInt()}% watched"
            val left = (rt * (1f - progress)).toInt().coerceAtLeast(1)
            return if (left >= 60) "${left / 60} h ${left % 60} min left" else "$left min left"
        }
}
