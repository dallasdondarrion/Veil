package dev.veil.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Makes the remote's Home button open Veil, and keeps Google's home screen from staying up after the TV
 * starts or wakes. It only looks at the Home key and at which app's window comes to the front (package and
 * screen name); it never reads screen content. All other keys pass through.
 */
class HomeButtonService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var captureKeyRelease: Int? = null
    private var activeMappedTarget: String? = null
    private var recentAppsOverlay: LinearLayout? = null
    private var recentOverlayPackages: List<String> = emptyList()
    private var recentOverlayTiles: List<View> = emptyList()
    private var recentOverlaySelected = 0

    /** True while the last real screen to come to the front was the TV's own home screen (or another launcher). */
    private var stockHomeInFront = false

    /** Whether any real screen has come to the front since this service started. */
    private var sawWindow = false

    private val reclaimCheck = Runnable { reclaimIfStockHome() }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scheduleReclaim()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(wakeReceiver, filter)
        }
        // The service starts a moment after the TV boots; Google's home screen may already be up with no window
        // event left to react to, so check once without waiting for one.
        val sinceBootMs = SystemClock.elapsedRealtime()
        Log.i("VeilHome", "Home helper connected ${sinceBootMs / 1000}s after boot")
        if (RECLAIM_AT_BOOT && sinceBootMs < BOOT_WINDOW_MS) scheduleReclaim()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (recentAppsOverlay != null) return handleRecentOverlayKey(event)
        val prefs = getSharedPreferences("veil", MODE_PRIVATE)
        // Consume the capture press so TV shortcuts (such as YouTube) cannot open their default app/store page.
        if (prefs.getBoolean("button_mapping_capture", false)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) captureKeyRelease = event.keyCode
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    if (captureKeyRelease == event.keyCode && !event.isCanceled) {
                        captureKeyRelease = null
                        prefs.edit().putBoolean("button_mapping_capture", false).apply()
                        try {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_CAPTURED_BUTTON, event.keyCode)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                        } catch (_: Exception) { }
                    }
                    return true
                }
                else -> return true
            }
        }
        if (captureKeyRelease == event.keyCode && event.action == KeyEvent.ACTION_UP) {
            captureKeyRelease = null
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            activeMappedTarget = runCatching {
                val mappings = org.json.JSONArray(prefs.getString("button_mappings", "[]"))
                (0 until mappings.length()).firstNotNullOfOrNull { i ->
                    val item = mappings.optJSONObject(i) ?: return@firstNotNullOfOrNull null
                    item.optString("target").takeIf { item.optInt("key", KeyEvent.KEYCODE_UNKNOWN) == event.keyCode }
                }
            }.getOrNull()
        }
        val target = activeMappedTarget
        if (target == null) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            try {
                if (target == "switcher") {
                    showRecentAppsOverlay()
                } else if (target.startsWith("app:")) {
                    val pkg = target.removePrefix("app:")
                    val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                        ?: packageManager.getLaunchIntentForPackage(pkg)
                    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(::startActivity)
                }
            } catch (_: Exception) { }
            activeMappedTarget = null
        }
        return true
    }

    /** Recent apps opens as an accessibility overlay, so the current app stays on screen underneath it. */
    private fun showRecentAppsOverlay() {
        dismissRecentAppsOverlay()
        val prefs = getSharedPreferences("veil", MODE_PRIVATE)
        val packages = prefs.getString("recent_app_packages", "").orEmpty().split('|').filter { it.isNotBlank() && dev.veil.launcher.data.RecentFilter.allowed(this, it) }.distinct()
        val entries = packages.mapNotNull { pkg ->
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg)
            Triple(pkg, label, intent)
        }.take(6)
        if (entries.isEmpty()) return
        recentOverlayPackages = entries.map { it.first }
        recentOverlaySelected = 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = rounded(Color.argb(244, 25, 29, 37), dp(24))
            addView(TextView(this@HomeButtonService).apply {
                text = "Recent apps"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(8))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val row = LinearLayout(this@HomeButtonService).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val tiles = entries.mapIndexed { index, (pkg, label, intent) ->
                LinearLayout(this@HomeButtonService).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(7), dp(8), dp(7), dp(7))
                    background = tileBackground(index == recentOverlaySelected)
                    addView(ImageView(this@HomeButtonService).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(TextView(this@HomeButtonService).apply {
                        text = label
                        setTextColor(Color.WHITE)
                        textSize = 10.5f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setPadding(0, dp(5), 0, 0)
                    }, LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT))
                    setOnClickListener { launchRecentApp(intent) }
                }.also { tile ->
                    val lp = LinearLayout.LayoutParams(dp(106), dp(88))
                    lp.marginStart = dp(3); lp.marginEnd = dp(3)
                    row.addView(tile, lp)
                }
            }
            recentOverlayTiles = tiles
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        recentAppsOverlay = root
        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(48)
                title = "Veil Recent Apps"
            }
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, params)
        } catch (e: Exception) {
            Log.w("VeilHome", "Could not show recent-app overlay", e)
            recentAppsOverlay = null
            recentOverlayTiles = emptyList()
        }
    }

    private fun handleRecentOverlayKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> dismissRecentAppsOverlay()
            KeyEvent.KEYCODE_HOME -> { dismissRecentAppsOverlay(); goHome() }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (event.repeatCount == 0) moveRecentSelection(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0) moveRecentSelection(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) {
                val pkg = recentOverlayPackages.getOrNull(recentOverlaySelected)
                val intent = pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it) }
                if (pkg != null && intent != null) launchRecentApp(intent) else dismissRecentAppsOverlay()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (event.repeatCount == 0) {
                val keep = recentOverlaySelected
                recentOverlayPackages.getOrNull(keep)?.let { closeApp(it); removeFromRecentList(it) }
                showRecentAppsOverlay()
                if (recentOverlayPackages.isNotEmpty()) {
                    recentOverlaySelected = keep.coerceAtMost(recentOverlayPackages.size - 1)
                    recentOverlayTiles.forEachIndexed { i, t -> t.background = tileBackground(i == recentOverlaySelected) }
                }
            }
            else -> return false
        }
        return true
    }

    private fun moveRecentSelection(delta: Int) {
        if (recentOverlayPackages.isEmpty()) return
        recentOverlaySelected = (recentOverlaySelected + delta).mod(recentOverlayPackages.size)
        recentOverlayTiles.forEachIndexed { index, tile -> tile.background = tileBackground(index == recentOverlaySelected) }
    }

    /** Ends the app's background processes (same method Horizon Home Kit uses); never touches Veil itself. */
    private fun closeApp(pkg: String) {
        if (pkg == packageName) return
        runCatching {
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg)
        }
    }

    private fun removeFromRecentList(pkg: String) {
        val prefs = getSharedPreferences("veil", MODE_PRIVATE)
        val recent = prefs.getString("recent_app_packages", "").orEmpty().split('|').filter { it.isNotBlank() && it != pkg }
        prefs.edit().putString("recent_app_packages", recent.joinToString("|")).apply()
    }

    private fun launchRecentApp(intent: Intent) {
        dismissRecentAppsOverlay()
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    private fun dismissRecentAppsOverlay() {
        val view = recentAppsOverlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
        recentAppsOverlay = null
        recentOverlayPackages = emptyList()
        recentOverlayTiles = emptyList()
        recentOverlaySelected = 0
    }


    private fun tileBackground(selected: Boolean) = rounded(
        if (selected) Color.argb(255, 74, 87, 103) else Color.TRANSPARENT,
        dp(16),
    ).apply { if (selected) setStroke(dp(2), Color.argb(240, 213, 226, 244)) }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // If Google's home screen comes up (after boot, wake or any system event), replace it.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Pop-ups (volume bar, dialogs, keyboards) say nothing about which screen the TV is on.
        val transient = pkg == "android" || pkg == "com.android.systemui" || cls.startsWith("android.")
        val stockHome = isStockHome(pkg, cls)
        if (!transient) {
            sawWindow = true
            stockHomeInFront = stockHome
            if (pkg != packageName && !stockHome && dev.veil.launcher.data.RecentFilter.allowed(this, pkg)) {
                val prefs = getSharedPreferences("veil", MODE_PRIVATE)
                val recent = prefs.getString("recent_app_packages", "").orEmpty().split('|').filter { it.isNotBlank() }
                prefs.edit().putString("recent_app_packages", (listOf(pkg) + recent.filterNot { it == pkg }).take(12).joinToString("|")).apply()
            }
            if (pkg == packageName) handler.removeCallbacks(reclaimCheck) // Veil is up; nothing left to reclaim.
        }
        if (stockHome) goHome()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stopWatching()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    private fun stopWatching() {
        dismissRecentAppsOverlay()
        handler.removeCallbacks(reclaimCheck)
        runCatching { unregisterReceiver(wakeReceiver) }
    }

    /** Re-checks a few times as the TV settles after a wake or boot; each check does nothing if Veil is up. */
    private fun scheduleReclaim() {
        handler.removeCallbacks(reclaimCheck)
        RECLAIM_DELAYS_MS.forEach { handler.postDelayed(reclaimCheck, it) }
    }

    private fun reclaimIfStockHome() {
        // Just booted and no screen has been seen yet: whatever is up is the TV's own start screen.
        val justBooted = RECLAIM_AT_BOOT && !sawWindow && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS
        // Otherwise act only if the last screen seen was a stock home. If Veil or an app you were watching is
        // in front, leave it alone.
        if (stockHomeInFront || justBooted) {
            Log.i("VeilHome", "reclaiming Home (stockHomeInFront=$stockHomeInFront, justBooted=$justBooted)")
            goHome()
        }
    }

    /** Only the TV's own home *screen* (not its other screens, like an apps list or settings), or another launcher. */
    private fun isStockHome(pkg: String, cls: String): Boolean =
        (pkg == GOOGLE_HOME && cls.endsWith("HomeActivity")) || otherHomes.any { (p, c) -> p == pkg && c == cls }

    private fun goHome() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        } catch (_: Exception) {
            // Never crash the service; Google's home screen remains as a fallback.
        }
    }

    /**
     * The home screens other than Veil installed on this TV (package, activity), e.g. Android TV's
     * com.google.android.tvlauncher or a TV maker's own launcher. Android's boot-time "FallbackHome"
     * in Settings is left out.
     */
    private val otherHomes: List<Pair<String, String>> by lazy {
        runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            ).map { it.activityInfo.packageName to it.activityInfo.name }
                .filter { (p, _) -> p != packageName && p != "com.android.settings" && p != "com.android.tv.settings" }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx"

        /**
         * Open Veil on its own right after the TV boots, before any window event arrives. Set to false if a TV that
         * boots straight into an HDMI input or live TV gets interrupted by Veil.
         */
        const val RECLAIM_AT_BOOT = true

        /** "Just booted" means within this long of power-on (time since boot, not since the screen last woke). */
        const val BOOT_WINDOW_MS = 3 * 60_000L

        /** Checks after a boot or wake, in ms. */
        val RECLAIM_DELAYS_MS = longArrayOf(400L, 1_200L, 2_500L)
    }
}
