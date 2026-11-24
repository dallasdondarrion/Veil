package dev.veil.launcher.data

import dev.veil.launcher.BuildConfig

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sync rules every tracker follows, as the services' own guides ask:
 * - the service's "last activities" is asked at most once a minute, however often Home is opened;
 * - each part of Continue Watching (paused items, next episodes) is fetched again only when the activity
 *   stamps it depends on moved, or when it's older than its own max age (for what isn't account activity,
 *   like a new episode airing);
 * - a forced pass (sign-in, after a hold-menu action, the "Nothing in progress" pill) fetches every part.
 * What was fetched and why goes to logcat under "VeilSync" (never any of the data itself).
 */
internal class SyncCache(context: Context, private val name: String) {
    private val prefs = context.getSharedPreferences("${name}_cache", Context.MODE_PRIVATE)

    class Part(val key: String, val maxAge: Long, val stamp: (JSONObject) -> String, val fetch: suspend () -> String)

    fun get(key: String): String? = prefs.getString(key, null)
    fun clear() = prefs.edit().clear().apply()
    /** The next pass fetches everything (after a write of our own). */
    fun invalidate() = prefs.edit().putBoolean("force", true).apply()

    suspend fun sync(force: Boolean, activities: () -> JSONObject, parts: List<Part>) {
        val now = System.currentTimeMillis()
        val all = force || prefs.getBoolean("force", false)
        val haveAll = parts.all { prefs.contains(it.key) }
        if (!all && haveAll && now - prefs.getLong("checked", 0) < CHECK_EVERY) return
        val a = runCatching { activities() }.getOrElse { error ->
            if (haveAll) {
                Log.w("VeilSync", "$name: activity check failed; using cached results", error)
                return
            }
            throw error
        }
        for (p in parts) {
            val stamp = p.stamp(a)
            val reason = when {
                all -> "forced"
                !prefs.contains(p.key) -> "first"
                stamp != prefs.getString("${p.key}_stamp", null) -> "activity"
                now - prefs.getLong("${p.key}_at", 0) > p.maxAge -> "max age"
                else -> null
            } ?: continue
            val fetched = runCatching { p.fetch() }
            if (fetched.isFailure) {
                Log.w("VeilSync", "$name: ${p.key} refresh failed; keeping cached results", fetched.exceptionOrNull())
                continue
            }
            val body = fetched.getOrThrow()
            prefs.edit().putString(p.key, body).putString("${p.key}_stamp", stamp).putLong("${p.key}_at", now).apply()
            if (BuildConfig.DEBUG) Log.d("VeilSync", "$name: fetched ${p.key} ($reason)")
        }
        prefs.edit().putLong("checked", now).putBoolean("force", false).apply()
    }

    companion object {
        const val CHECK_EVERY = 60_000L
        const val HOUR = 3600_000L

        /** Stamps from a nested activities object, e.g. stamps(a, "movies.paused_at", "episodes.watched_at"). */
        fun stamps(a: JSONObject, vararg paths: String): String = paths.joinToString("|") { path ->
            var o: JSONObject? = a
            val keys = path.split('.')
            keys.dropLast(1).forEach { o = o?.obj(it) }
            o?.str(keys.last()).orEmpty()
        }

        /** For parts that are already Veil's own items (Trakt's up next is built from several calls). */
        fun encode(items: List<WatchItem>): String = JSONArray().apply {
            items.forEach { i ->
                put(JSONObject().apply {
                    put("movie", i.isMovie); put("title", i.title)
                    i.traktId?.let { put("trakt", it) }; i.imdb?.let { put("imdb", it) }
                    i.tmdb?.let { put("tmdb", it) }; i.tvdb?.let { put("tvdb", it) }
                    i.serviceId?.let { put("sid", it) }
                    put("s", i.season); put("e", i.episode); i.episodeTitle?.let { put("et", it) }
                    put("p", i.progress.toDouble()); i.runtime?.let { put("rt", it) }
                    put("up", i.upNext); put("new", i.isNew); i.playbackId?.let { put("pb", it) }
                    put("t", i.sortTime)
                })
            }
        }.toString()

        fun decode(text: String?): List<WatchItem> {
            val arr = text?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
            return (0 until arr.length()).mapNotNull { k ->
                val j = arr.optJSONObject(k) ?: return@mapNotNull null
                WatchItem(
                    isMovie = j.optBoolean("movie"), title = j.str("title") ?: "Untitled",
                    traktId = j.int("trakt"), imdb = j.str("imdb"), tmdb = j.int("tmdb"), tvdb = j.int("tvdb"),
                    serviceId = j.str("sid"), season = j.optInt("s"), episode = j.optInt("e"), episodeTitle = j.str("et"),
                    progress = j.optDouble("p", 0.0).toFloat(), runtime = j.int("rt"), upNext = j.optBoolean("up"),
                    isNew = j.optBoolean("new"), playbackId = j.optLong("pb").takeIf { it > 0 }, sortTime = j.optLong("t"),
                )
            }
        }
    }
}
