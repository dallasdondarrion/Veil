package dev.veil.launcher.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Builds the "Continue watching" row from Trakt (which Nuvio syncs to):
 *  1. Paused episodes/movies (/sync/playback), with progress.
 *  2. The next unwatched episode of recently watched shows (/shows/{id}/progress/watched).
 */
object ContinueWatching {
    private const val MAX_ITEMS = 10
    private const val MAX_UP_NEXT_SHOWS = 10
    /** An episode counts as "NEW" for two weeks after it airs (if you haven't watched the show since). */
    internal const val NEW_WINDOW_MS = 14L * 24 * 3600 * 1000

    /**
     * Adds TMDB artwork to any tracker's items. Items without a TMDB id (Simkl often sends only IMDb/TVDB
     * ids) are looked up through TMDB's /find first.
     */
    suspend fun withArt(items: List<WatchItem>, tmdb: Tmdb): List<WatchItem> = coroutineScope {
        items.map { item ->
            async {
                val id = item.tmdb ?: runCatching { tmdb.find(item.isMovie, item.imdb, item.tvdb) }.getOrNull()
                    ?: return@async item
                val artResult = runCatching { tmdb.art(item.isMovie, id) }
val art = artResult.getOrNull() ?: return@async item.copy(tmdb = id)
                                item.copy(
                    tmdb = id,
                    backdrop = art.backdrop,
                    thumb = art.thumb,
                    logo = art.logo,
                    poster = art.poster,
                    runtime = item.runtime?.takeIf { it > 0 }
                        ?: art.runtime
                        ?: if (!item.isMovie) tmdb.episodeRuntime(id, item.season, item.episode) else null
                )
            }
        }.awaitAll()
    }

    /**
     * Trakt: paused items (/sync/playback) and up next (built from history + one progress call per show), each
     * fetched again only when /sync/last_activities moved for it (see [SyncCache]). Trakt's next episode is
     * always an aired one, so up next is also refreshed every few hours to pick up new airings.
     */
    internal suspend fun load(trakt: Trakt, sync: SyncCache, force: Boolean): List<WatchItem> {
        sync.sync(force, { JSONObject(trakt.get("/sync/last_activities")) }, listOf(
            SyncCache.Part("playback", 24 * SyncCache.HOUR, {
                SyncCache.stamps(it, "movies.paused_at", "episodes.paused_at", "movies.watched_at", "episodes.watched_at")
            }) { trakt.get("/sync/playback?limit=30&extended=full") },
            SyncCache.Part("upnext", 3 * SyncCache.HOUR, {
                SyncCache.stamps(it, "episodes.watched_at", "shows.hidden_at", "seasons.hidden_at")
            }) { SyncCache.encode(upNext(trakt, pausedShows(parsePlayback(sync.get("playback"))))) },
        ))
        val playback = parsePlayback(sync.get("playback"))
        val paused = pausedShows(playback)
        return merge(playback, SyncCache.decode(sync.get("upnext")).filter { it.traktId !in paused })
    }

    private fun pausedShows(playback: List<WatchItem>) = playback.filter { !it.isMovie }.mapNotNull { it.traktId }.toSet()

    private suspend fun upNext(trakt: Trakt, pausedShows: Set<Int>): List<WatchItem> = coroutineScope {
        // Shows you removed from Continue Watching (Trakt "hidden from progress") stay out of Up Next.
        val hidden = trakt.hiddenShows()

        // Most recently watched shows that aren't already paused mid-episode.
        val history = JSONArray(trakt.get("/sync/history/episodes?limit=60&extended=full"))
        val recentShows = LinkedHashMap<Int, Pair<JSONObject, Long>>()
        for (i in 0 until history.length()) {
            val h = history.getJSONObject(i)
            val show = h.obj("show") ?: continue
            val id = show.obj("ids")?.int("trakt") ?: continue
            if (id in pausedShows || id in hidden || id in recentShows) continue
            recentShows[id] = show to time(h.str("watched_at"))
            if (recentShows.size >= MAX_UP_NEXT_SHOWS) break
        }

        val upNext = recentShows.map { (id, pair) ->
            async {
                runCatching {
                    val p = JSONObject(
                        trakt.get("/shows/$id/progress/watched?hidden=false&specials=false&count_specials=false&extended=full"),
                    )
                    p.obj("next_episode")?.let { next0 ->
                        // Air date + runtime for "NEW" and "done by". If the progress call didn't include them,
                        // ask Trakt's documented episode endpoint. Missing data just means no badge.
                        var next = next0
                        if (next.str("first_aired") == null) {
                            val s = next.int("season"); val e = next.int("number")
                            if (s != null && e != null) runCatching {
                                next = JSONObject(trakt.get("/shows/$id/seasons/$s/episodes/$e?extended=full"))
                            }
                        }
                        val aired = time(next.str("first_aired"))
                        val lastWatched = time(p.str("last_watched_at")).takeIf { it > 0 } ?: pair.second
                        val now = System.currentTimeMillis()
                        val isNew = aired in (lastWatched + 1)..now && now - aired < NEW_WINDOW_MS
                        showItem(pair.first, next, progress = 0f, upNext = true, sortTime = maxOf(pair.second, if (isNew) aired else 0L))
                            .let { it.copy(isNew = isNew, runtime = it.runtime ?: pair.first.int("runtime")) }
                    }
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        upNext
    }

    /** Paused items and next episodes together: newest first, one entry per show/movie, at most [MAX_ITEMS]. */
    fun merge(playback: List<WatchItem>, upNext: List<WatchItem>): List<WatchItem> =
        (playback + upNext)
            .sortedByDescending { it.sortTime }
            .distinctBy { it.key }
            .take(MAX_ITEMS)

    private fun parsePlayback(text: String?): List<WatchItem> {
        val arr = text?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<WatchItem>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val progress = (j.optDouble("progress", 0.0) / 100.0).toFloat()
            if (progress >= 0.95f) continue
            val t = time(j.str("paused_at"))
            when (j.str("type")) {
                "episode" -> {
                    val show = j.obj("show") ?: continue
                    val ep = j.obj("episode") ?: continue
                    out += showItem(show, ep, progress, upNext = false, sortTime = t).copy(playbackId = j.optLong("id").takeIf { it > 0 })
                }
                "movie" -> {
                    val m = j.obj("movie") ?: continue
                    val ids = m.obj("ids")
                    out += WatchItem(
                        isMovie = true,
                        title = m.str("title") ?: "Untitled",
                        traktId = ids?.int("trakt"),
                        imdb = ids?.str("imdb"),
                        tmdb = ids?.int("tmdb"),
                        progress = progress,
                        runtime = m.int("runtime"),
                        playbackId = j.optLong("id").takeIf { it > 0 },
                        sortTime = t,
                    )
                }
            }
        }
        return out
    }

    private fun showItem(show: JSONObject, ep: JSONObject, progress: Float, upNext: Boolean, sortTime: Long): WatchItem {
        val ids = show.obj("ids")
        return WatchItem(
            isMovie = false,
            title = show.str("title") ?: "Untitled",
            traktId = ids?.int("trakt"),
            imdb = ids?.str("imdb"),
            tmdb = ids?.int("tmdb"),
            season = ep.int("season") ?: 0,
            episode = ep.int("number") ?: 0,
            episodeTitle = ep.str("title"),
            progress = progress,
            runtime = ep.int("runtime"),
            upNext = upNext,
            sortTime = sortTime,
        )
    }

    internal fun time(iso: String?): Long =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}
