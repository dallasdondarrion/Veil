package dev.veil.launcher.data

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.util.Log

/** Reads only the selected app's public Android TV Watch Next entries. */
object NuvioWatchNext {
    data class Result(val items: List<WatchItem>, val error: String? = null)

    private val IMDB_ID = Regex("tt\\d+")

    fun load(context: Context, packageName: String): Result {
        val out = ArrayList<WatchItem>()
        return try {
            context.contentResolver.query(
                TvContract.WatchNextPrograms.CONTENT_URI,
                null,
                null,
                null,
                "last_engagement_time_utc_millis DESC",
            )?.use { cursor ->
                val packageCol = cursor.getColumnIndex("package_name")
                val titleCol = cursor.getColumnIndex("title")
                while (cursor.moveToNext()) {
                    val pkg = value(cursor, packageCol) ?: continue
                    if (pkg != packageName) continue
                    val title = value(cursor, titleCol)?.takeIf { it.isNotBlank() } ?: continue
                    val seasonText = value(cursor, cursor.getColumnIndex("season_display_number"))
                    val episodeText = value(cursor, cursor.getColumnIndex("episode_display_number"))
                    val episodeTitle = value(cursor, cursor.getColumnIndex("episode_title"))
                    // Nuvio puts the title's id, type and episode in the card's launch request. The id (an IMDb id
                    // like "tt0944947") lets Veil fetch the right TMDB artwork and runtime instead of guessing by title.
                    val intentUri = value(cursor, cursor.getColumnIndex("intent_uri"))
                    val launch = intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
                    val imdb = launch?.getStringExtra("contentId")?.takeIf { IMDB_ID.matches(it) }
                    val launchType = launch?.getStringExtra("contentType")
                    val isMovie = when {
                        launchType.equals("movie", ignoreCase = true) -> true
                        launchType != null -> false
                        else -> seasonText.isNullOrBlank() && episodeText.isNullOrBlank()
                    }
                    val season = seasonText?.toIntOrNull() ?: launch?.getIntExtra("season", 0) ?: 0
                    val episode = episodeText?.toIntOrNull() ?: launch?.getIntExtra("episode", 0) ?: 0
                    val durationMs = number(cursor, "duration_millis")
                    val positionMs = number(cursor, "last_playback_position_millis")
                    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    if (progress >= 0.95f) continue
                    val engaged = number(cursor, "last_engagement_time_utc_millis")
                    val poster = value(cursor, cursor.getColumnIndex("poster_art_uri"))
                    val thumb = value(cursor, cursor.getColumnIndex("thumbnail_uri"))
                    val logo = value(cursor, cursor.getColumnIndex("logo_uri"))
                    out += WatchItem(
                        isMovie = isMovie,
                        title = title,
                        traktId = null,
                        imdb = imdb,
                        tmdb = null,
                        season = season,
                        episode = episode,
                        episodeTitle = episodeTitle,
                        progress = progress,
                        // Nuvio publishes a placeholder 100,000 ms duration when it doesn't know the real length.
                        runtime = durationMs.takeIf { it > 0 && it != 100_000L }?.let { (it / 60_000L).toInt() }?.takeIf { it > 0 },
                        sortTime = engaged,
                        thumb = thumb ?: poster,
                        poster = poster,
                        logo = logo,
                        nativeIntentUri = intentUri,
                        nativePackage = pkg,
                    )
                }
            }
            // Provider rows can linger or duplicate a title as playback is updated. Keep only
            // the newest entry per movie/show; the timestamp must not be part of its identity.
            val latestByTitle = out.sortedByDescending { it.sortTime }.distinctBy { item ->
                "${item.nativePackage}:${if (item.isMovie) "m" else "s"}:${item.title.trim().lowercase()}"
            }
            Result(latestByTitle.take(10))
        } catch (se: SecurityException) {
            Log.w("VeilNuvio", "No permission to read Android TV Watch Next", se)
            Result(emptyList(), "Android TV didn't allow access to Watch Next")
        } catch (t: Throwable) {
            Log.w("VeilNuvio", "Watch Next query failed", t)
            Result(emptyList(), "Couldn't read Nuvio + IPTV's Continue Watching")
        }
    }

    /**
     * Adds TMDB artwork and runtime. The IMDb id from Nuvio's launch request picks the title exactly; only rows
     * without one fall back to matching by title. Whatever TMDB can't supply keeps the app's own card art.
     * Makes network calls, so call it off the main thread.
     */
    fun enrich(items: List<WatchItem>, tmdb: Tmdb): List<WatchItem> = items.map { item ->
        val id = tmdb.find(item.isMovie, item.imdb, null) ?: tmdb.search(item.isMovie, item.title)
        val art = id?.let { tmdb.art(item.isMovie, it) }
        val episodeRuntime = if (id != null && !item.isMovie) tmdb.episodeRuntime(id, item.season, item.episode) else null
        item.copy(
            tmdb = id ?: item.tmdb,
            backdrop = art?.backdrop ?: item.backdrop,
            thumb = item.thumb ?: art?.thumb,
            logo = item.logo ?: art?.logo,
            poster = item.poster ?: art?.poster,
            // Nuvio's own length when it is real, else this episode's length, else the show's usual episode length.
            runtime = item.runtime?.takeIf { it > 0 } ?: episodeRuntime ?: art?.runtime?.takeIf { it > 0 },
        )
    }

    private fun value(cursor: android.database.Cursor, index: Int): String? =
        if (index < 0 || cursor.isNull(index)) null else runCatching { cursor.getString(index) }.getOrNull()

    private fun number(cursor: android.database.Cursor, column: String): Long {
        val index = cursor.getColumnIndex(column)
        return if (index < 0 || cursor.isNull(index)) 0L else runCatching { cursor.getLong(index) }.getOrDefault(0L)
    }
}
