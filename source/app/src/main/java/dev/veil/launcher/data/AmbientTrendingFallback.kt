package dev.veil.launcher.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares the configured daily or weekly trending pool between Home and Veil Ambient. */
object AmbientTrendingFallback {
    private const val CACHE_MS = 6 * 60 * 60 * 1000L

    private val lock = Mutex()
    private var cachedClient: Tmdb? = null

    suspend fun load(client: Tmdb, window: String, movieCount: Int, showCount: Int): List<WatchItem> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            lock.withLock {
                val now = System.currentTimeMillis()
                val key = "$window:$movieCount:$showCount"
                if (cachedClient === client && VeilCache.ambientTrendingKey == key && now - VeilCache.ambientTrendingAt < CACHE_MS) {
                    return@withLock VeilCache.ambientTrending
                }
                runCatching { client.ambientTrending(window, movieCount, showCount) }
                    .getOrDefault(emptyList())
                    .also {
                        cachedClient = client
                        VeilCache.ambientTrending = it
                        VeilCache.ambientTrendingKey = key
                        VeilCache.ambientTrendingAt = now
                    }
            }
        }
}
