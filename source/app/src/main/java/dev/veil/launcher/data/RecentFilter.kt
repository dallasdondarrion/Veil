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
}
