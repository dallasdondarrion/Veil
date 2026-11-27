package dev.veil.launcher.data

import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Looks up artwork (backdrop, title logo, poster), runtimes and ids from TMDB, cached in memory. */
class Tmdb(private val key: String) {
    data class Art(
        val backdrop: String?,
        val thumb: String?,
        val logo: String?,
        val poster: String?,
        val runtime: Int?,
    )

    private val artCache = ConcurrentHashMap<String, Art>()
    private val episodeRuntimeCache = ConcurrentHashMap<String, Int>()
    private val findCache = ConcurrentHashMap<String, Int>()
    private val titleSearchCache = ConcurrentHashMap<String, Int>()

    val enabled: Boolean get() = key.isNotBlank()

    /** A v4 read-access token (starts with "eyJ") is sent as a header; a v3 api key goes in the URL. */
    private val isV4Token: Boolean get() = key.startsWith("eyJ")

    /** GETs a TMDB endpoint. [params] are already URL-encoded "name=value" pairs. Null unless TMDB answers 200. */
    private fun get(path: String, vararg params: String): JSONObject? {
        if (!enabled) return null
        val query = (params.toList() + if (isV4Token) emptyList() else listOf("api_key=$key")).joinToString("&")
        val url = "$API/$path" + if (query.isEmpty()) "" else "?$query"
        val headers = if (isV4Token) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val (code, text) = runCatching { Http.call("GET", url, headers) }.getOrNull() ?: return null
        if (code != 200) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    fun art(isMovie: Boolean, id: Int): Art? {
        val type = if (isMovie) "movie" else "tv"
        artCache["$type:$id"]?.let { return it }
        val j = get("$type/$id", "append_to_response=images", "include_image_language=en,null") ?: return null
        val logo = bestLogo(j.obj("images")?.optJSONArray("logos"))
        val art = Art(
            // "original" is typically 3840x2160; Coil decodes it at the hero's size, so it stays sharp when the
            // 1080p UI is scaled up to the 4K panel.
            backdrop = j.str("backdrop_path")?.let { "$IMG/original$it" },
            thumb = j.str("backdrop_path")?.let { "$IMG/w780$it" },
            logo = logo?.let { "$IMG/original$it" }, // small PNGs; sharp on the 4K panel
            poster = j.str("poster_path")?.let { "$IMG/w342$it" },
            runtime = typicalRuntime(isMovie, j),
        )
        artCache["$type:$id"] = art
        return art
    }

    /** Top daily or weekly trending titles with backdrops, suitable for Home and Ambient backgrounds. */
    fun ambientTrending(window: String, movieCount: Int, showCount: Int): List<WatchItem> =
        trending(isMovie = true, limit = movieCount, window = window) +
            trending(
                isMovie = false,
                limit = showCount,
                excludedGenres = setOf(TV_NEWS, TV_REALITY, TV_TALK),
                window = window,
            )

    private fun trending(
        isMovie: Boolean,
        limit: Int,
        excludedGenres: Set<Int> = emptySet(),
        window: String = "day",
    ): List<WatchItem> {
        if (!enabled || limit <= 0) return emptyList()
        val media = if (isMovie) "movie" else "tv"
        val period = if (window == "week") "week" else "day"
        val found = ArrayList<WatchItem>(limit)
        val gameShowText = Regex("\\b(game show|quiz show|trivia show|late[- ]night)\\b", RegexOption.IGNORE_CASE)
        for (page in 1..4) {
            val results = get("trending/$media/$period", "language=en-US", "page=$page")
                ?.optJSONArray("results") ?: break
            for (i in 0 until results.length()) {
                val result = results.optJSONObject(i) ?: continue
                if (result.optBoolean("adult")) continue
                val genres = result.optJSONArray("genre_ids")
                if (genres != null && (0 until genres.length()).any { genres.optInt(it) in excludedGenres }) continue
                val title = result.str(if (isMovie) "title" else "name")?.takeIf { it.isNotBlank() } ?: continue
                if (!isMovie && gameShowText.containsMatchIn("$title ${result.str("overview").orEmpty()}")) continue
                val releaseDate = result.str(if (isMovie) "release_date" else "first_air_date")
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
                if (releaseDate.isAfter(LocalDate.now())) continue
                val id = result.optInt("id", 0).takeIf { it > 0 } ?: continue
                val backdropPath = result.str("backdrop_path") ?: continue
                val posterPath = result.str("poster_path")
                found += WatchItem(
                    isMovie = isMovie,
                    title = title,
                    traktId = null,
                    imdb = null,
                    tmdb = id,
                    backdrop = "$IMG/original$backdropPath",
                    thumb = "$IMG/w780$backdropPath",
                    poster = posterPath?.let { "$IMG/w342$it" },
                    isTrending = true,
                    overview = result.str("overview")?.takeIf { it.isNotBlank() },
                    releaseYear = releaseDate.year.toString(),
                )
                if (found.size == limit) return found
            }
        }
        return found
    }

    /** Minutes for one episode of [season]/[episode] of the show [tvId], or null if TMDB has none. */
    fun episodeRuntime(tvId: Int, season: Int, episode: Int): Int? {
        if (season <= 0 || episode <= 0) return null
        val cacheKey = "$tvId:$season:$episode"
        episodeRuntimeCache[cacheKey]?.let { return it }
        val runtime = get("tv/$tvId/season/$season/episode/$episode")
            ?.optInt("runtime", 0)?.takeIf { it > 0 } ?: return null
        episodeRuntimeCache[cacheKey] = runtime
        return runtime
    }

    /** TMDB id for an IMDb (or TVDB) id, via TMDB's /find. Cached; null when TMDB doesn't know it. */
    fun find(isMovie: Boolean, imdb: String?, tvdb: Int?): Int? {
        val (source, ext) = when {
            imdb != null -> "imdb_id" to imdb
            tvdb != null && !isMovie -> "tvdb_id" to tvdb.toString()
            else -> return null
        }
        val cacheKey = "$isMovie:$source:$ext"
        findCache[cacheKey]?.let { return it }
        val results = get("find/$ext", "external_source=$source")
            ?.optJSONArray(if (isMovie) "movie_results" else "tv_results") ?: return null
        val id = results.optJSONObject(0)?.int("id") ?: return null
        findCache[cacheKey] = id
        return id
    }

    /** Finds a TMDB movie or TV id by exact title match (ignoring case and punctuation). Cached. */
    fun search(isMovie: Boolean, title: String): Int? {
        if (title.isBlank()) return null
        val type = if (isMovie) "movie" else "tv"
        val cacheKey = "$type:${title.trim().lowercase()}"
        titleSearchCache[cacheKey]?.let { return it }
        val query = URLEncoder.encode(title.trim(), "UTF-8")
        val results = get("search/$type", "query=$query")?.optJSONArray("results") ?: return null
        val wanted = normalize(title)
        for (i in 0 until results.length()) {
            val hit = results.optJSONObject(i) ?: continue
            val hitTitle = hit.str(if (isMovie) "title" else "name") ?: continue
            if (normalize(hitTitle) != wanted) continue
            val id = hit.int("id") ?: continue
            titleSearchCache[cacheKey] = id
            return id
        }
        return null
    }

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    /** Best-rated English PNG logo (SVGs can't be drawn without an extra decoder), else any PNG. */
    private fun bestLogo(logos: JSONArray?): String? {
        if (logos == null) return null
        for (englishOnly in listOf(true, false)) {
            var best = -1.0
            var path: String? = null
            for (i in 0 until logos.length()) {
                val l = logos.getJSONObject(i)
                val file = l.str("file_path") ?: continue
                if (!file.endsWith(".png")) continue
                if (englishOnly && l.str("iso_639_1") != "en") continue
                val score = l.optDouble("vote_average", 0.0) + l.optInt("vote_count", 0) * 0.01
                if (score > best) { best = score; path = file }
            }
            if (path != null) return path
        }
        return null
    }

    /** A movie's runtime, or a show's usual episode length, in minutes. */
    private fun typicalRuntime(isMovie: Boolean, j: JSONObject): Int? =
        if (isMovie) {
            j.optInt("runtime", 0).takeIf { it > 0 }
        } else {
            j.optJSONArray("episode_run_time")?.let { minutes ->
                (0 until minutes.length()).map { minutes.optInt(it) }.firstOrNull { it > 0 }
            }
        }

    private companion object {
        const val API = "https://api.themoviedb.org/3"
        const val IMG = "https://image.tmdb.org/t/p"
        const val TV_NEWS = 10763
        const val TV_REALITY = 10764
        const val TV_TALK = 10767
    }
}
