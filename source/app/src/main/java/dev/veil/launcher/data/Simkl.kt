package dev.veil.launcher.data

import android.content.Context
import dev.veil.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Simkl, signed in with the device code ("AUTH V2", public app: client ID only). Continue Watching is
 * paused playbacks plus the next episode of shows you're watching. Simkl asks apps not to poll its sync
 * endpoints, so they're only fetched again when /sync/activities says something changed.
 */
class Simkl(context: Context) : Tracker {
    override val service = Service.SIMKL
    private val clientId = BuildConfig.SIMKL_CLIENT_ID
    private val auth = DeviceAuth(
        context, "Simkl", clientId,
        deviceUrl = "$API/oauth2/device", tokenUrl = "$API/oauth2/token",
        scope = "media:read media:write",
    )
    private val sync = SyncCache(context, "simkl")

    override val hasKeys get() = auth.hasKeys
    override val isConnected get() = auth.isConnected
    override fun requestDeviceCode() = auth.requestDeviceCode()
    override fun pollDeviceToken(dc: DeviceCode) = auth.pollDeviceToken(dc)
    override fun disconnect() { auth.disconnect(); sync.clear() }

    /** client_id, app-name and app-version go on every Simkl request. */
    private fun url(path: String): String {
        val sep = if ('?' in path) "&" else "?"
        return API + path + sep + "client_id=" + DeviceAuth.enc(clientId) +
            "&app-name=veil&app-version=" + DeviceAuth.enc(BuildConfig.VERSION_NAME)
    }

    private fun headers() = mapOf(
        "Authorization" to "Bearer ${auth.accessToken}",
        "simkl-api-key" to clientId,
    )

    private fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        auth.refreshIfNeeded()
        var r = Http.call(method, url(path), headers(), body?.toString())
        if (r.first == 401 && auth.refreshIfNeeded(force = true)) r = Http.call(method, url(path), headers(), body?.toString())
        return r
    }

    private fun get(path: String): String {
        val (code, text) = call("GET", path)
        if (code !in 200..299) throw IOException("Simkl returned $code")
        return text
    }

    override suspend fun continueWatching(force: Boolean): List<WatchItem> {
        sync.sync(force, { JSONObject(get("/sync/activities")) }, listOf(
            SyncCache.Part("playback", 24 * SyncCache.HOUR, {
                SyncCache.stamps(it, "tv_shows.playback", "anime.playback", "movies.playback")
            }) { get("/sync/playback") },
            SyncCache.Part("shows", 6 * SyncCache.HOUR, { SyncCache.stamps(it, "tv_shows.all") }) {
                get("/sync/all-items/shows/watching?next_watch_info=yes")
            },
            SyncCache.Part("anime", 6 * SyncCache.HOUR, { SyncCache.stamps(it, "anime.all") }) {
                runCatching { get("/sync/all-items/anime/watching?next_watch_info=yes") }.getOrDefault("")
            },
        ))
        val playback = sync.get("playback")
        val shows = sync.get("shows")
        val anime = sync.get("anime")
        val paused = parsePlayback(arrayOf(playback))
        val pausedShows = paused.filter { !it.isMovie }.mapNotNull { it.serviceId }.toSet()
        val upNext = parseWatching(shows, "shows") + parseWatching(anime, "anime")
        return ContinueWatching.merge(paused, upNext.filter { it.serviceId !in pausedShows })
    }

    private fun arrayOf(text: String?): JSONArray =
        text?.trim()?.let { if (it.startsWith("[")) JSONArray(it) else null } ?: JSONArray()

    private fun parsePlayback(arr: JSONArray): List<WatchItem> {
        val out = ArrayList<WatchItem>()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val progress = (j.optDouble("progress", 0.0) / 100.0).toFloat()
            if (progress >= 0.95f) continue
            val t = ContinueWatching.time(j.str("paused_at"))
            val pid = j.optLong("id").takeIf { it > 0 }
            if (j.str("type") == "movie") {
                val m = j.obj("movie") ?: continue
                out += item(m, isMovie = true).copy(progress = progress, playbackId = pid, sortTime = t)
            } else {
                val show = j.obj("show") ?: j.obj("anime") ?: continue
                val ep = j.obj("episode") ?: continue
                out += item(show, isMovie = false).copy(
                    season = ep.int("season") ?: 1, episode = ep.int("number") ?: ep.int("episode") ?: 0,
                    episodeTitle = ep.str("title"), progress = progress, playbackId = pid, sortTime = t,
                )
            }
        }
        return out
    }

    /** The next episode of each show in your "watching" list (shows or anime). */
    private fun parseWatching(text: String?, key: String): List<WatchItem> {
        val arr = text?.let { runCatching { JSONObject(it).optJSONArray(key) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<WatchItem>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val show = j.obj("show") ?: j.obj("anime") ?: continue
            val info = j.obj("next_to_watch_info")
            val code = Regex("""S(\d+)E(\d+)""").find(j.str("next_to_watch").orEmpty())
            val season = info?.int("season") ?: code?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val episode = info?.int("episode") ?: code?.groupValues?.get(2)?.toIntOrNull() ?: continue
            val lastWatched = ContinueWatching.time(j.str("last_watched_at"))
            val aired = ContinueWatching.time(info?.str("date"))
            if (aired > now) continue // not out yet
            val isNew = aired in (lastWatched + 1)..now && now - aired < ContinueWatching.NEW_WINDOW_MS
            out += item(show, isMovie = false).copy(
                season = season, episode = episode, episodeTitle = info?.str("title"),
                upNext = true, isNew = isNew, sortTime = maxOf(lastWatched, if (isNew) aired else 0L),
            )
            if (out.size >= 10) break
        }
        return out
    }

    private fun item(o: JSONObject, isMovie: Boolean): WatchItem {
    val ids = o.obj("ids")
    return WatchItem(
        isMovie = isMovie,
        title = o.str("title") ?: "Untitled",
        traktId = null,
        imdb = ids?.str("imdb"),
        tmdb = ids?.str("tmdb")?.toIntOrNull(),
        tvdb = ids?.str("tvdb")?.toIntOrNull(),
        serviceId = ids?.str("simkl"),
        runtime = o.int("runtime"),
    )
}

    /** Simkl ids for a write: its own number when known, else IMDb/TMDB. */
    private fun ids(item: WatchItem) = JSONObject().apply {
        item.serviceId?.toLongOrNull()?.let { put("simkl", it) }
        item.imdb?.let { put("imdb", it) }
        item.tmdb?.let { put("tmdb", it) }
    }

    private fun ok(code: Int) = code in 200..299

    override fun markWatched(item: WatchItem): Boolean {
        val now = java.time.Instant.now().toString()
        val body = if (item.isMovie) {
            JSONObject().put("movies", JSONArray().put(JSONObject().put("ids", ids(item)).put("watched_at", now)))
        } else {
            JSONObject().put("shows", JSONArray().put(
                JSONObject().put("ids", ids(item)).put("seasons", JSONArray().put(
                    JSONObject().put("number", item.season).put("episodes", JSONArray().put(
                        JSONObject().put("number", item.episode).put("watched_at", now),
                    )),
                )),
            ))
        }
        val done = ok(call("POST", "/sync/history", body).first)
        if (done) item.playbackId?.let { call("DELETE", "/sync/playback/$it") }
        sync.invalidate()
        return done
    }

    /** Clears paused progress; for shows also moves them to "On hold" on Simkl (undo there). */
    override fun removeFromProgress(item: WatchItem): Boolean {
        var done = true
        item.playbackId?.let { done = ok(call("DELETE", "/sync/playback/$it").first) }
        if (!item.isMovie) {
            val body = JSONObject().put("shows", JSONArray().put(JSONObject().put("to", "hold").put("ids", ids(item))))
            done = done && ok(call("POST", "/sync/add-to-list", body).first)
        }
        sync.invalidate()
        return done
    }

    companion object {
        const val API = "https://api.simkl.com"
        /** Refetch at least this often anyway, so newly aired episodes show up. */
    }
}
