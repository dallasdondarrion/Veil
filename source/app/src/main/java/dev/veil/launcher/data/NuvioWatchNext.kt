package dev.veil.launcher.data

import android.content.Context
import android.media.tv.TvContract
import android.util.Log

/** Reads only the selected app's public Android TV Watch Next entries. */
object NuvioWatchNext {
    data class Result(val items: List<WatchItem>, val error: String? = null)

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
                    val season = seasonText?.toIntOrNull() ?: 0
                    val episode = episodeText?.toIntOrNull() ?: 0
                    val durationMs = number(cursor, "duration_millis")
                    val positionMs = number(cursor, "last_playback_position_millis")
                    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    if (progress >= 0.95f) continue
                    val engaged = number(cursor, "last_engagement_time_utc_millis")
                    val poster = value(cursor, cursor.getColumnIndex("poster_art_uri"))
                    val thumb = value(cursor, cursor.getColumnIndex("thumbnail_uri"))
                    val logo = value(cursor, cursor.getColumnIndex("logo_uri"))
                    val intentUri = value(cursor, cursor.getColumnIndex("intent_uri"))
                    out += WatchItem(
                        isMovie = seasonText.isNullOrBlank() && episodeText.isNullOrBlank(),
                        title = title,
                        traktId = null,
                        imdb = null,
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

    private fun value(cursor: android.database.Cursor, index: Int): String? =
        if (index < 0 || cursor.isNull(index)) null else runCatching { cursor.getString(index) }.getOrNull()

    private fun number(cursor: android.database.Cursor, column: String): Long {
        val index = cursor.getColumnIndex(column)
        return if (index < 0 || cursor.isNull(index)) 0L else runCatching { cursor.getLong(index) }.getOrDefault(0L)
    }
}
