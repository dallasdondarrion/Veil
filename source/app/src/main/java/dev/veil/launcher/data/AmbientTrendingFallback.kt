package dev.veil.launcher.data

import dev.veil.launcher.BuildConfig

/** Shares the small daily trending fallback between Home and Veil Ambient. */
object AmbientTrendingFallback {
    private const val CACHE_MS = 6 * 60 * 60 * 1000L

    suspend fun load(): List<WatchItem> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - VeilCache.ambientTrendingAt < CACHE_MS) return@withContext VeilCache.ambientTrending
        runCatching { Tmdb(BuildConfig.TMDB_KEY).ambientTrending() }
            .getOrDefault(emptyList())
            .also {
                VeilCache.ambientTrending = it
                VeilCache.ambientTrendingAt = now
            }
    }
}
