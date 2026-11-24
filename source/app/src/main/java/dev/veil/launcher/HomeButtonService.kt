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
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes the remote's Home button open Veil, and keeps Google's home screen from staying up after the TV
 * starts or wakes. It only looks at the Home key and at which app's window comes to the front (package and
 * screen name); it never reads screen content. All other keys pass through.
 */
class HomeButtonService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

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
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
        return true
    }

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
