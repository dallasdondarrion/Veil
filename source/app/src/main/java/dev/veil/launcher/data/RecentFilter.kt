package dev.veil.launcher.data

/** Keeps system screens (Settings, Google's home panel, input/setup helpers) out of Recent Apps. */
object RecentFilter {
    private val blockedParts = listOf(
        "settings", "launcher", "tvrecommendations", "katniss", "systemui", "packageinstaller",
        "permissioncontroller", "inputmethod", "gboard", "tv.setup", "setupwizard", "tvsystem",
        "com.google.android.apps.tv.", "com.google.android.tungsten", "com.android.providers",
    )

    /** True only for apps you installed (or updated from the Play Store), not built-in system apps. */
    fun allowed(context: android.content.Context, pkg: String): Boolean {
        if (!allowed(pkg)) return false
        val flags = runCatching { context.packageManager.getApplicationInfo(pkg, 0).flags }.getOrNull() ?: return false
        val system = flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        val updated = flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return !system || updated
    }

    fun allowed(pkg: String): Boolean {
        if (pkg == "android") return false
        val p = pkg.lowercase()
        return blockedParts.none { p.contains(it) }
    }

    // Apps closed with Down stay "closed" until you open them again on purpose (Veil, a mapped button or the
    // recents popup). Closing kills the process but not its Android task, so backing out of another app
    // can bring the closed app's window up again; that must not put it back in the list.
    private const val CLOSED_KEY = "closed_apps"

    private fun closed(prefs: android.content.SharedPreferences) =
        prefs.getString(CLOSED_KEY, "").orEmpty().split('|').filter { it.isNotBlank() }

    fun isClosed(prefs: android.content.SharedPreferences, pkg: String) = pkg in closed(prefs)

    fun markClosed(prefs: android.content.SharedPreferences, pkg: String) {
        prefs.edit().putString(CLOSED_KEY, (closed(prefs).filterNot { it == pkg } + pkg).takeLast(30).joinToString("|")).apply()
    }

    fun clearClosed(prefs: android.content.SharedPreferences, pkg: String) {
        if (isClosed(prefs, pkg)) prefs.edit().putString(CLOSED_KEY, closed(prefs).filterNot { it == pkg }.joinToString("|")).apply()
    }
}
