package dev.veil.launcher.data

import dev.veil.launcher.BuildConfig

/** Shares the configured daily or weekly trending pool between Home and Veil Ambient. */
object AmbientTrendingFallback {
    private const val CACHE_MS = 6 * 60 * 60 * 1000L

    suspend fun load(window: String, movieCount: Int, showCount: Int): List<WatchItem> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val key = "$window:$movieCount:$showCount"
            if (VeilCache.ambientTrendingKey == key && now - VeilCache.ambientTrendingAt < CACHE_MS) {
                return@withContext VeilCache.ambientTrending
            }
            runCatching { Tmdb(BuildConfig.TMDB_KEY).ambientTrending(window, movieCount, showCount) }
                .getOrDefault(emptyList())
                .also {
                    VeilCache.ambientTrending = it
                    VeilCache.ambientTrendingKey = key
                    VeilCache.ambientTrendingAt = now
                }
        }
}
