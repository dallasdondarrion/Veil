package dev.veil.launcher.data

/**
 * Latest data the home screen has loaded, kept in memory so the Veil Ambient screensaver can show it
 * instantly (it runs in the same process). The screensaver falls back to loading it itself.
 */
object VeilCache {
    @Volatile var items: List<WatchItem> = emptyList()
    @Volatile var ambientTrending: List<WatchItem> = emptyList()
    @Volatile var ambientTrendingAt: Long = 0L
    @Volatile var weather: Weather.Now? = null
    @Volatile var blocked: Int? = null
    @Volatile var weeklyBlocked: Int? = null
}
