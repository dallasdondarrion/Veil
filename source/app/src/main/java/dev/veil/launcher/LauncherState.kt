package dev.veil.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.tv.TvContract
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.veil.launcher.data.AppEntry
import dev.veil.launcher.data.Apps
import dev.veil.launcher.data.BadClientId
import dev.veil.launcher.data.ContinueWatching
import dev.veil.launcher.data.DeviceCode
import dev.veil.launcher.data.MdbList
import dev.veil.launcher.data.WeTrakr
import dev.veil.launcher.data.Poll
import dev.veil.launcher.data.Service
import dev.veil.launcher.data.Simkl
import dev.veil.launcher.data.Tracker
import dev.veil.launcher.data.NextDnsStats
import dev.veil.launcher.data.NowPlaying
import dev.veil.launcher.data.NuvioWatchNext
import dev.veil.launcher.data.PrivateDns
import dev.veil.launcher.data.ScreensaverControl
import dev.veil.launcher.data.Weather
import dev.veil.launcher.data.Tmdb
import dev.veil.launcher.data.Trakt
import dev.veil.launcher.data.VeilCache
import dev.veil.launcher.data.WatchItem
import dev.veil.launcher.ui.ThemeState
import dev.veil.launcher.ui.VeilThemeSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, DRAWER, CONNECT }

/** The hold-OK menu: which app it is for, and whether it was opened from the Home row. */
data class AppSheet(val app: AppEntry, val fromHome: Boolean)
data class ButtonMapping(val keyCode: Int, val target: String)

/**
 * @Stable: everything the UI reads from here is Compose state (or never changes, like permissions), so
 * screens that receive this object can be skipped when nothing they show has changed.
 */
@androidx.compose.runtime.Stable
class LauncherState(private val context: Context, private val scope: CoroutineScope) {
    val trakt = Trakt(context)
    private val simkl = Simkl(context)
    private val mdblist = MdbList(context)
    private val wetrakr = WeTrakr(context)
    private val tmdb = Tmdb(BuildConfig.TMDB_KEY)
    private val prefs = context.getSharedPreferences("veil", Context.MODE_PRIVATE)

    /** The one tracker Continue Watching comes from (Trakt unless you picked another). */
    var service by mutableStateOf(Service.of(prefs.getString("tracker", null)))
        private set
    val tracker: Tracker get() = trackerFor(service)
    private fun trackerFor(s: Service): Tracker = when (s) {
        Service.TRAKT -> trakt
        Service.SIMKL -> simkl
        Service.MDBLIST -> mdblist
        Service.WETRAKR -> wetrakr
    }
    /** Whether a service has a client ID in this build (otherwise it's shown as not available). */
    fun available(s: Service) = trackerFor(s).hasKeys || s == Service.TRAKT

    var screen by mutableStateOf(Screen.HOME)
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var items by mutableStateOf<List<WatchItem>>(emptyList())
        private set
    var focused by mutableStateOf<WatchItem?>(null)
    /** Index of the last focused Continue Watching item, for the "x of N" counter. */
    var focusedIndex by mutableStateOf(0)
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(tracker.isConnected)
        private set
    var deviceCode by mutableStateOf<DeviceCode?>(null)
        private set
    /** Connect screen step 1: pick Trakt, Simkl, MDBList or WeTrakr. Step 2 is the sign-in code. */
    var pickingService by mutableStateOf(true)
        private set
    var connectStatus by mutableStateOf("")
        private set
    /** Connect screen shows the "your own Trakt app" client ID field instead of a sign-in code. */
    var editingClientId by mutableStateOf(false)
        private set
    var sheet by mutableStateOf<AppSheet?>(null)
    var appSwitcherOpen by mutableStateOf(false)
        private set
    enum class SettingsPage { PLAYER, AMBIENT }
    var settingsPage by mutableStateOf<SettingsPage?>(null)
    var buttonMappingOpen by mutableStateOf(false)
    var buttonMappingChoosingTarget by mutableStateOf(false)
    var buttonMappingCapture by mutableStateOf(prefs.getBoolean("button_mapping_capture", false))
        private set
    var buttonMappings by mutableStateOf(loadButtonMappings())
        private set
    private var pendingButtonKeyCode: Int? = null
    init {
        if (prefs.getString("button_mappings", null) == null && buttonMappings.isNotEmpty()) saveButtonMappings()
    }
    var recentAppPackages by mutableStateOf(loadRecentAppPackages())
        private set
    val recentApps: List<AppEntry> get() = recentAppPackages.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }

    /** Ordered package names pinned to the Home row. */
    var pins by mutableStateOf(loadPins())
        private set

    // ---- Top-bar extras: ad blocking, blocked counter, weather, now playing ----
    private val dns = PrivateDns(context)
    private val weatherSource = Weather(context)
    val nowPlaying = NowPlaying(context)
    val dnsAvailable: Boolean get() = dns.canControl
    var dnsOn by mutableStateOf(dns.isOn)
        private set
    /** When a pause ends (epoch ms), 0 when not paused. */
    var dnsResumeAt by mutableStateOf(dns.resumeAt)
        private set
    val dnsPaused: Boolean get() = dnsResumeAt > 0L && !dnsOn
    /** Updated every minute so "paused · 14 min" counts down. */
    var minuteTick by mutableStateOf(System.currentTimeMillis())
        private set
    var dnsBusy by mutableStateOf(false)
        private set
    /** Short-lived message on the privacy pill, e.g. when ad blocking had to be turned back off. */
    var dnsMessage by mutableStateOf<String?>(null)
        private set
    var blockedToday by mutableStateOf<Int?>(null)
        private set
    var weather by mutableStateOf<Weather.Now?>(null)
        private set
    /** True while the privacy pill is focused, which shows the details panel under it. */
    var privacyFocused by mutableStateOf(false)
        private set
    var dnsDetails by mutableStateOf<NextDnsStats.Details?>(null)
        private set
    val dnsProvider: String get() = if (NextDnsStats.enabled) "NextDNS" else "AdGuard DNS"
    val usesNextDns: Boolean get() = NextDnsStats.enabled
    private var lastDetails = 0L

    fun onPrivacyFocus(focused: Boolean) {
        privacyFocused = focused
        if (!focused || !NextDnsStats.enabled) return
        val now = System.currentTimeMillis()
        if (now - lastDetails < 60_000) return
        lastDetails = now
        scope.launch {
            withContext(Dispatchers.IO) { NextDnsStats.details() }?.let {
                dnsDetails = it
                blockedToday = it.blocked
            }
            withContext(Dispatchers.IO) { NextDnsStats.weekly() }?.let { setWeekly(it) }
        }
    }

    /** Last 7 days of blocking, for the privacy panel and Sunday's Ambient line. */
    var dnsWeekly by mutableStateOf<NextDnsStats.Weekly?>(null)
        private set
    private var lastWeekly = 0L
    private fun setWeekly(w: NextDnsStats.Weekly) {
        dnsWeekly = w
        VeilCache.weeklyBlocked = w.blocked
        lastWeekly = System.currentTimeMillis()
    }

    /** Snow in December, fireworks at New Year… in Veil Ambient only. */
    var seasonal by mutableStateOf(prefs.getBoolean("seasonal", true))
        private set
    fun toggleSeasonal() {
        seasonal = !seasonal
        prefs.edit().putBoolean("seasonal", seasonal).apply()
    }
    private var lastWeather = 0L
    private var lastStats = 0L

    /** Bumped every time Home is pressed, so the UI can move focus back to the start. */
    var homeTick by mutableStateOf(0)
        private set

    private var lastLoad = 0L
    private var lastNativeLoad = 0L
    private var watchLoadJob: Job? = null
    private var watchRequest = 0L
    private var connectJob: Job? = null

    val pinnedApps: List<AppEntry>
        get() = pins.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }

    private var hiddenAppPackages by mutableStateOf(
        prefs.getStringSet("hidden_apps", emptySet())?.toSet() ?: emptySet(),
    )
    var revealHiddenApps by mutableStateOf(false)
        private set
    val drawerApps: List<AppEntry>
        get() = if (revealHiddenApps) apps else apps.filterNot { it.pkg in hiddenAppPackages }

    fun toggleRevealHiddenApps() { revealHiddenApps = !revealHiddenApps }

    fun isHiddenFromAllApps(app: AppEntry) = app.pkg in hiddenAppPackages

    fun hideFromAllApps(app: AppEntry) {
        hiddenAppPackages = hiddenAppPackages + app.pkg
        prefs.edit().putStringSet("hidden_apps", hiddenAppPackages).apply()
    }

    fun showInAllApps(app: AppEntry) {
        hiddenAppPackages = hiddenAppPackages - app.pkg
        prefs.edit().putStringSet("hidden_apps", hiddenAppPackages).apply()
    }

    fun isPinned(app: AppEntry) = app.pkg in pins

    // ---- Pins ---------------------------------------------------------------------------------

    private fun loadPins(): List<String> {
        val saved = prefs.getString("pins", null) ?: return DEFAULT_PINS
        return saved.split('|').filter { it.isNotBlank() }
    }

    private fun savePins(list: List<String>) {
        pins = list
        prefs.edit().putString("pins", list.joinToString("|")).apply()
    }

    fun pin(app: AppEntry) { if (app.pkg !in pins) savePins(pins + app.pkg) }
    fun unpin(app: AppEntry) = savePins(pins - app.pkg)

    /** Moves a pinned app one step left (-1) or right (+1) among the apps that are installed. */
    fun move(app: AppEntry, delta: Int) {
        val visible = pinnedApps.map { it.pkg }.toMutableList()
        val i = visible.indexOf(app.pkg)
        val j = i + delta
        if (i < 0 || j !in visible.indices) return
        visible[i] = visible[j].also { visible[j] = visible[i] }
        // Keep pins for apps that are currently uninstalled at the end, so they return if reinstalled.
        savePins(visible + pins.filter { it !in visible })
    }

    // ---- Navigation ---------------------------------------------------------------------------

    fun onHomePressed() {
        if (screen == Screen.CONNECT) cancelConnect()
        sheet = null
        privacySheet = false
        musicSheet = false
        resetIdle()
        screen = Screen.HOME
        homeTick++
    }

    private var lastAppsLoad = 0L
    private fun loadRecentAppPackages(): List<String> = prefs.getString("recent_app_packages", "").orEmpty()
        .split('|').filter { it.isNotBlank() }.distinct().take(12)

    private fun saveRecentAppPackage(pkg: String) {
        if (pkg == context.packageName) return
        recentAppPackages = (listOf(pkg) + recentAppPackages.filterNot { it == pkg }).take(12)
        prefs.edit().putString("recent_app_packages", recentAppPackages.joinToString("|")).apply()
    }

    fun openAppSwitcher() {
        recentAppPackages = loadRecentAppPackages()
        appSwitcherOpen = true
        refreshApps(force = true)
    }

    fun closeAppSwitcher() { appSwitcherOpen = false }

    fun closeButtonMapping() {
        cancelButtonMappingCapture()
        buttonMappingOpen = false
    }

    private fun loadButtonMappings(): List<ButtonMapping> {
        val stored = prefs.getString("button_mappings", null)
        if (stored != null) return runCatching {
            val array = org.json.JSONArray(stored)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val key = item.optInt("key", KeyEvent.KEYCODE_UNKNOWN)
                val target = item.optString("target").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (key == KeyEvent.KEYCODE_UNKNOWN || (target != "switcher" && !target.startsWith("app:"))) null
                else ButtonMapping(key, target)
            }
        }.getOrDefault(emptyList())
        val legacyKey = prefs.getInt("app_switcher_key", KeyEvent.KEYCODE_UNKNOWN)
        return if (legacyKey == KeyEvent.KEYCODE_UNKNOWN) emptyList()
        else listOf(ButtonMapping(legacyKey, "switcher"))
    }

    private fun saveButtonMappings() {
        val array = org.json.JSONArray()
        buttonMappings.forEach { mapping ->
            array.put(org.json.JSONObject().put("key", mapping.keyCode).put("target", mapping.target))
        }
        prefs.edit().putString("button_mappings", array.toString()).apply()
    }

    fun beginButtonMappingCapture() {
        pendingButtonKeyCode = null
        buttonMappingChoosingTarget = false
        buttonMappingCapture = true
        prefs.edit().putBoolean("button_mapping_capture", true).apply()
        showToast("Press the remote button to map")
    }

    fun cancelButtonMappingCapture() {
        buttonMappingCapture = false
        buttonMappingChoosingTarget = false
        pendingButtonKeyCode = null
        prefs.edit().putBoolean("button_mapping_capture", false).apply()
    }

    fun completeButtonMapping(target: String) {
        val keyCode = pendingButtonKeyCode ?: return
        buttonMappings = (buttonMappings.filterNot { it.keyCode == keyCode } + ButtonMapping(keyCode, target))
        pendingButtonKeyCode = null
        buttonMappingChoosingTarget = false
        saveButtonMappings()
        showToast("Mapped ${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")} to ${mappingTargetLabel(target)}")
    }

    fun removeButtonMapping(keyCode: Int) {
        buttonMappings = buttonMappings.filterNot { it.keyCode == keyCode }
        saveButtonMappings()
    }

    fun mappingTargetLabel(target: String): String = when {
        target == "switcher" -> "Recent apps"
        target.startsWith("app:") -> apps.firstOrNull { it.pkg == target.removePrefix("app:") }?.label
            ?: target.removePrefix("app:")
        else -> target
    }

    fun receiveCapturedButton(keyCode: Int) {
        captureButtonMapping(keyCode)
    }

    private fun captureButtonMapping(keyCode: Int) {
        if (keyCode in setOf(
                KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_POWER,
            )) {
            showToast("Choose a dedicated remote shortcut button")
            return
        }
        pendingButtonKeyCode = keyCode
        buttonMappingCapture = false
        buttonMappingChoosingTarget = true
        prefs.edit().putBoolean("button_mapping_capture", false).apply()
        showToast("Button captured. Choose what it opens")
    }

    fun dismissRecentApp(pkg: String) {
        recentAppPackages = recentAppPackages.filterNot { it == pkg }
        prefs.edit().putString("recent_app_packages", recentAppPackages.joinToString("|")).apply()
    }

    fun refreshApps(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAppsLoad < 60_000) return
        lastAppsLoad = now
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { Apps.load(context) }.getOrNull() } ?: return@launch
            if (loaded != apps) apps = loaded
        }
    }

    private val watchHandler = Handler(Looper.getMainLooper())
    private val watchNextRefresh = Runnable { if (player.usesWatchNext) refreshWatching(force = true) }
    private val watchNextObserver = object : ContentObserver(watchHandler) {
        override fun onChange(selfChange: Boolean) {
            android.util.Log.i("VeilNuvio", "Watch Next changed; refreshing Nuvio + IPTV row")
            // Nuvio updates its row several times in a burst; wait for it to settle.
            watchHandler.removeCallbacks(watchNextRefresh)
            watchHandler.postDelayed(watchNextRefresh, 800)
        }
    }
    private var watchingNextChanges = false

    /** Refreshes the Nuvio + IPTV row as soon as that app updates its Android TV Watch Next entries. */
    fun startWatchNextUpdates() {
        // Nuvio holds its Watch Next update back while video plays and writes it as it leaves the screen, which can
        // land just after Veil's own first read. Re-read a couple of times after coming back as a safety net.
        watchHandler.removeCallbacks(watchNextRefresh)
        watchHandler.postDelayed(watchNextRefresh, 2_500)
        watchHandler.postDelayed(watchNextRefresh, 7_000)
        if (watchingNextChanges) return
        watchingNextChanges = runCatching {
            context.contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, watchNextObserver)
            true
        }.getOrDefault(false)
    }

    fun stopWatchNextUpdates() {
        if (!watchingNextChanges) return
        watchHandler.removeCallbacks(watchNextRefresh)
        runCatching { context.contentResolver.unregisterContentObserver(watchNextObserver) }
        watchingNextChanges = false
    }

    fun refreshWatching(force: Boolean = false) {
    connected = tracker.isConnected
    val selected = player
    val now = System.currentTimeMillis()

    if (selected.usesWatchNext) {
        if (!force && now - lastNativeLoad < 60_000) return
        val watchPkg = installedPackage(selected)
        if (watchPkg == null) {
            items = emptyList(); VeilCache.items = emptyList()
            error = "${selected.title} isn't installed"
            loading = false
            return
        }
        lastNativeLoad = now
        val request = ++watchRequest
        watchLoadJob?.cancel()
        watchLoadJob = scope.launch {
            loading = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val loaded = NuvioWatchNext.load(context, watchPkg)

                    val enriched = NuvioWatchNext.enrich(loaded.items, tmdb)

                    loaded.copy(items = enriched)
                }

                if (request != watchRequest || player != selected) return@launch

                items = result.items
                VeilCache.items = result.items
                val keep = result.items.firstOrNull { it.key == focused?.key }
                focused = keep ?: result.items.firstOrNull()
                focusedIndex = if (keep != null) result.items.indexOf(keep) else 0
                error = result.error
            } finally {
                if (request == watchRequest) loading = false
            }
        }
        return
    }

    if (!connected) {
        watchLoadJob?.cancel(); ++watchRequest
        items = emptyList(); VeilCache.items = emptyList()
        focused = null; focusedIndex = 0
        loading = false; error = null
        return
    }
    if (!force && now - lastLoad < 60_000) return
    lastLoad = now
    val request = ++watchRequest
    watchLoadJob?.cancel()
    watchLoadJob = scope.launch {
        loading = true
        try {
            val source = tracker
            val result = withContext(Dispatchers.IO) {
                ContinueWatching.withArt(source.continueWatching(force), tmdb)
            }
            if (request != watchRequest || source !== tracker || player != selected) return@launch
            items = result
            VeilCache.items = result
            val keep = result.firstOrNull { it.key == focused?.key }
            focused = keep ?: result.firstOrNull()
            focusedIndex = if (keep != null) result.indexOf(keep) else 0
            error = null
        } catch (e: Exception) {
            if (request == watchRequest) error = "Couldn't reach ${service.title}"
        } finally {
            if (request == watchRequest) {
                loading = false
                connected = tracker.isConnected
            }
        }
    }
}

    // ---- Ad blocking, counter, weather ----------------------------------------------------------

    /** One press toggles ad blocking. Turning on is verified and automatically undone if the network breaks. */
    /** OK on the pill: on â†’ off; off or paused â†’ on. */
    fun toggleDns() = changeDns(if (dns.isOn) DnsAction.OFF else DnsAction.ON)

    fun pauseDns(minutes: Int) = changeDns(DnsAction.PAUSE, minutes)
    fun turnDnsOn() = changeDns(DnsAction.ON)
    fun turnDnsOff() = changeDns(DnsAction.OFF)

    private enum class DnsAction { ON, OFF, PAUSE }

    private fun changeDns(action: DnsAction, minutes: Int = 0) {
        if (dnsBusy || !dns.canControl) return
        scope.launch {
            dnsBusy = true
            dnsMessage = null
            try {
                when (action) {
                    DnsAction.OFF -> { withContext(Dispatchers.IO) { dns.clearPause(); dns.turnOff() }; dev.veil.launcher.ui.Sfx.off() }
                    DnsAction.PAUSE -> { withContext(Dispatchers.IO) { dns.pause(minutes) }; dev.veil.launcher.ui.Sfx.off() }
                    DnsAction.ON -> {
                        withContext(Dispatchers.IO) { dns.clearPause() }
                        val ok = withContext(Dispatchers.IO) { dns.turnOnSafely() }
                        if (!ok) { dev.veil.launcher.ui.Sfx.error(); flash("Ad blocking failed, turned off") }
                        else { dev.veil.launcher.ui.Sfx.on(); lastStats = 0; refreshExtras() }
                    }
                }
            } catch (e: Exception) {
                runCatching { dns.turnOff() }
                flash("Ad blocking failed, turned off")
            } finally {
                dnsOn = dns.isOn
                dnsResumeAt = dns.resumeAt
                dnsBusy = false
                lastDetails = 0
                if (privacyFocused) onPrivacyFocus(true)
            }
        }
    }

    private fun flash(msg: String) {
        dnsMessage = msg
        scope.launch { delay(6_000); if (dnsMessage == msg) dnsMessage = null }
    }

    // ---- "Update available" (Veil menu row + a dot on the "veil" button; never a popup) -----------

    /** Daily check for a newer Veil on GitHub. Default from the build (UPDATE_CHECK), switchable on the TV. */
    var updateCheck by mutableStateOf(prefs.getBoolean("update_check", BuildConfig.UPDATE_CHECK))
        private set
    private var latestUpdate by mutableStateOf(
        prefs.getString("update_sha", null)?.let {
            dev.veil.launcher.data.UpdateCheck.Update(it, prefs.getLong("update_time", 0), prefs.getString("update_title", "").orEmpty())
        },
    )
    /** "Hide until the next update" belongs to the build it was chosen on; a reinstall shows it again. */
    private var hiddenUpdate by mutableStateOf(
        prefs.getString("update_hidden", null)?.takeIf { prefs.getLong("update_hidden_src", 0) == BuildConfig.SOURCE_TIME },
    )
    /** The bigger "Update available" window, opened from the Veil menu row. */
    var updateDialog by mutableStateOf(false)

    /**
     * The newer version to mention, if any. "Newer" means changed on GitHub well after this build's own
     * source (2 h of slack: the public commit is made a little after the code it publishes).
     */
    val availableUpdate: dev.veil.launcher.data.UpdateCheck.Update?
        get() = latestUpdate?.takeIf {
            updateCheck && it.sha != hiddenUpdate && it.time > BuildConfig.SOURCE_TIME * 1000 + 2 * 3600_000L
        }

    fun toggleUpdateCheck() {
        updateCheck = !updateCheck
        prefs.edit().putBoolean("update_check", updateCheck).putLong("update_checked", 0).apply()
        if (updateCheck) checkForUpdate()
    }

    /** "Hide until the next update": forgets this one; a newer commit shows again. */
    fun hideUpdate() {
        hiddenUpdate = latestUpdate?.sha
        prefs.edit().putString("update_hidden", hiddenUpdate).putLong("update_hidden_src", BuildConfig.SOURCE_TIME).apply()
    }

    /** At most once a day, never in the first minutes after boot, silent on any failure. */
    private fun checkForUpdate() {
        if (!updateCheck) return
        val now = System.currentTimeMillis()
        if (android.os.SystemClock.elapsedRealtime() < 3 * 60_000L) return
        if (now - prefs.getLong("update_checked", 0) < 24 * 3600_000L) return
        prefs.edit().putLong("update_checked", now).apply()
        scope.launch {
            val u = withContext(Dispatchers.IO) { dev.veil.launcher.data.UpdateCheck.latest() } ?: return@launch
            latestUpdate = u
            prefs.edit().putString("update_sha", u.sha).putLong("update_time", u.time).putString("update_title", u.title).apply()
        }
    }

    /** Weather every 30 min, blocked counter every 5 min. Cheap to call often. */
    fun refreshExtras() {
        checkForUpdate()
        dnsOn = dns.isOn
        dnsResumeAt = dns.resumeAt
        val now = System.currentTimeMillis()
        minuteTick = now
        // A pause that ended while the alarm was delayed: resume now.
        if (dnsResumeAt in 1..now && !dnsOn && !dnsBusy) turnDnsOn()
        if (weatherSource.enabled && now - lastWeather > 30 * 60_000) {
            lastWeather = now
            scope.launch { withContext(Dispatchers.IO) { weatherSource.current() }?.let { weather = it; VeilCache.weather = it } }
        }
        if (NextDnsStats.enabled && now - lastWeekly > 30 * 60_000) {
            lastWeekly = now
            scope.launch { withContext(Dispatchers.IO) { NextDnsStats.weekly() }?.let { setWeekly(it) } }
        }
        if (NextDnsStats.enabled && dnsOn && now - lastStats > 5 * 60_000) {
            lastStats = now
            scope.launch {
                blockedToday = withContext(Dispatchers.IO) { NextDnsStats.blockedLast24h() } ?: blockedToday
                VeilCache.blocked = blockedToday
            }
        }
    }

    // ---- Hold-OK menus on the top-bar pills ------------------------------------------------------

    var privacySheet by mutableStateOf(false)
    var musicSheet by mutableStateOf(false)
    /** Hold menu for a Continue Watching card. */
    var watchSheet by mutableStateOf<WatchItem?>(null)

    // ---- Short confirmation message at the bottom of the screen ----
    var toast by mutableStateOf<String?>(null)
        private set
    private var toastJob: Job? = null
    fun showToast(msg: String) {
        toast = msg
        toastJob?.cancel()
        toastJob = scope.launch { delay(3_500); toast = null }
    }

    // ---- Continue Watching actions (written to the tracker, not just hidden here) ----

    private fun label(item: WatchItem) = if (item.isMovie) item.title else "${item.title} S${item.season} E${item.episode}"

    /** Adds it to your watch history on the tracker; the row then moves on to the next episode. */
    fun markWatched(item: WatchItem) {
        if (item.isNativeWatchNext) return
        trackerAction(item, "Marked ${label(item)} as watched") { tracker.markWatched(item) }
    }

    /** Clears progress on the tracker and takes the show out of its Up Next. */
    fun removeFromRow(item: WatchItem) {
        if (item.isNativeWatchNext) return
        trackerAction(item, "Removed ${item.title} from Continue Watching") { tracker.removeFromProgress(item) }
    }

    private fun trackerAction(item: WatchItem, done: String, call: () -> Boolean) {
        items = items.filterNot { it.key == item.key }          // instant feedback
        if (focused?.key == item.key) focused = items.firstOrNull()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching(call).getOrDefault(false) }
            if (ok) { dev.veil.launcher.ui.Sfx.success(); showToast(done) }
            else { dev.veil.launcher.ui.Sfx.error(); showToast("Couldn't reach ${service.title}. Nothing was changed") }
            lastLoad = 0
            refreshWatching(force = true)                          // pick up the next episode / restore on failure
        }
    }

    // ---- Interface sounds ----
    var sounds by mutableStateOf(dev.veil.launcher.ui.Sfx.enabled)
        private set
    fun toggleSounds() {
        sounds = !sounds
        dev.veil.launcher.ui.Sfx.setEnabled(context, sounds)
        if (sounds) dev.veil.launcher.ui.Sfx.on()
    }

    // ---- Lyrics ----
    /** Synced lyrics next to the album art in Veil music mode. */
    var lyricsMusic by mutableStateOf(context.getSharedPreferences("veil", Context.MODE_PRIVATE).getBoolean("lyrics_music", true))
        private set
    fun toggleLyricsMusic() {
        lyricsMusic = !lyricsMusic
        context.getSharedPreferences("veil", Context.MODE_PRIVATE).edit().putBoolean("lyrics_music", lyricsMusic).apply()
        if (lyricsMusic) dev.veil.launcher.ui.Sfx.on() else dev.veil.launcher.ui.Sfx.off()
    }

    // ---- Colours from artwork ----
    val artColors: Boolean get() = dev.veil.launcher.ui.ArtTint.enabled
    fun toggleArtColors() {
        val on = !artColors
        dev.veil.launcher.ui.ArtTint.setEnabled(context, on)
        if (on) dev.veil.launcher.ui.Sfx.on() else dev.veil.launcher.ui.Sfx.off()
    }

    // ---- Veil menu (drops down from the wordmark) -------------------------------------------------

    var veilMenuOpen by mutableStateOf(false)
    /** Bumped when OK opens the Veil menu, so focus moves into it. */
    var veilMenuFocusTick by mutableStateOf(0)
    var vizSettingsOpen by mutableStateOf(false)
    /** Which Veil-menu button should get focus back after its window closes ("viz", "weather"). */
    var menuReturn by mutableStateOf<String?>(null)
    /** True while the wordmark (or its menu) has focus; used to undo Android's default focus landing there. */
    var wordmarkFocused = false
    /** The last item used on Home ("app:<pkg>", "cw:<key>", "all-apps"); Home returns focus to it. */
    var lastFocusKey: String? = null
    var weatherPicker by mutableStateOf(false)
    var weatherPlace by mutableStateOf(weatherSource.placeName)
        private set
    val weatherHasOverride: Boolean get() = weatherSource.hasOverride
    val weatherDefault: String get() = weatherSource.defaultName

    fun setWeatherPlace(place: Weather.Place) {
        weatherSource.setOverride(place)
        afterWeatherChange()
    }

    fun resetWeatherPlace() {
        weatherSource.clearOverride()
        afterWeatherChange()
    }

    private fun afterWeatherChange() {
        weatherPlace = weatherSource.placeName
        weather = null
        VeilCache.weather = null
        lastWeather = 0
        refreshExtras()
    }

    fun setTheme(theme: VeilThemeSpec) = ThemeState.set(context, theme)

    /** Music mode in Veil Ambient: "veil" (album art) or "viz" (visualizer). */
    var musicStyle by mutableStateOf(prefs.getString("music_style", "veil") ?: "veil")
        private set
    val visualizerAllowed: Boolean
        get() = visualizerSupported &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** False on TVs without OpenGL ES 3.0 (the visualizer can't run there). */
    val visualizerSupported: Boolean by lazy { dev.veil.launcher.viz.VizSettings.supported(context) }

    fun chooseMusicStyle(style: String) {
        musicStyle = style
        prefs.edit().putString("music_style", style).apply()
    }

    /** Moves focus back to the first Continue Watching pill (e.g. after closing a menu). */
    fun focusHome() { homeTick++ }

    // ---- Veil Ambient (idle mode / music mode) ---------------------------------------------------

    var ambient by mutableStateOf(false)
        private set
    private var lastInput = System.currentTimeMillis()
    private var swallowKey: Int? = null

    var ambientTrendingWindow by mutableStateOf(if (prefs.getString("ambient_trending_window", "day") == "week") "week" else "day")
        private set
    var ambientMovieCount by mutableStateOf(prefs.getInt("ambient_movie_count", 5).coerceIn(0, 10))
        private set
    var ambientShowCount by mutableStateOf(prefs.getInt("ambient_show_count", 5).coerceIn(0, 10))
        private set
    var ambientSwitchSeconds by mutableStateOf(prefs.getInt("ambient_switch_seconds", 14).coerceIn(5, 120))
        private set

    fun chooseAmbientTrendingWindow(window: String) {
        if (window !in setOf("day", "week") || window == ambientTrendingWindow) return
        ambientTrendingWindow = window
        prefs.edit().putString("ambient_trending_window", window).apply()
    }

    fun chooseAmbientMovieCount(count: Int) {
        val selected = count.coerceIn(0, 10)
        if (selected == ambientMovieCount) return
        ambientMovieCount = selected
        prefs.edit().putInt("ambient_movie_count", selected).apply()
    }

    fun chooseAmbientShowCount(count: Int) {
        val selected = count.coerceIn(0, 10)
        if (selected == ambientShowCount) return
        ambientShowCount = selected
        prefs.edit().putInt("ambient_show_count", selected).apply()
    }

    fun chooseAmbientSwitchSeconds(seconds: Int) {
        val selected = seconds.coerceIn(5, 120)
        if (selected == ambientSwitchSeconds) return
        ambientSwitchSeconds = selected
        prefs.edit().putInt("ambient_switch_seconds", selected).apply()
    }

    fun startAmbient() { ambient = true }

    // ---- Veil Ambient as the TV's screensaver --------------------------------------------------------

    private val screensaver = ScreensaverControl(context)
    private var screensaverTick by mutableStateOf(0) // bumped after every change so the menu redraws

    @Suppress("UNUSED_EXPRESSION")
    val screensaverIsVeil: Boolean get() { screensaverTick; return screensaver.isVeil }

    @Suppress("UNUSED_EXPRESSION")
    val screensaverMinutes: Int? get() { screensaverTick; return screensaver.timeoutMinutes }

    fun toggleScreensaver() {
        if (!screensaver.canChooseScreensaver) {
            showToast("Needs a one-time permission over adb (WRITE_SECURE_SETTINGS)"); return
        }
        val turnOn = !screensaver.isVeil
        // Remember what was selected so switching off puts the TV's own screensaver back.
        if (turnOn) prefs.edit().putString("screensaver_previous", screensaver.current ?: "").apply()
        val previous = prefs.getString("screensaver_previous", null)?.takeIf { it.isNotBlank() }
        val ok = screensaver.setVeil(turnOn, previous)
        if (!ok) showToast("Couldn't change the TV's screensaver")
        screensaverTick++
    }

    fun chooseScreensaverMinutes(minutes: Int) {
        if (!screensaver.canSetTimeout) {
            showToast("Allow once over adb: appops set dev.veil.launcher WRITE_SETTINGS allow"); return
        }
        if (!screensaver.setTimeoutMinutes(minutes)) showToast("Couldn't change the screensaver time")
        screensaverTick++
    }

    // ---- Wake-up intro -----------------------------------------------------------------------------

    enum class Intro { NONE, ARMED, PLAYING }
    /** ARMED = screen went off; shows a plain background so waking never flashes the old Home first. */
    var intro by mutableStateOf(Intro.NONE)
    private var screenOffAt = 0L
    private var screenOnAt = 0L

    fun playIntro() { ambient = false; intro = Intro.PLAYING }

    fun onScreenOff() {
        screenOffAt = android.os.SystemClock.elapsedRealtime()
        intro = Intro.ARMED
    }

    fun onScreenOn(veilResumed: Boolean) {
        screenOnAt = android.os.SystemClock.elapsedRealtime()
        if (intro == Intro.ARMED && veilResumed) playIntro()
    }

    /** Veil came to the front: play the intro if this is a wake-up, otherwise drop the armed state. */
    fun onResumedForIntro() {
        if (intro != Intro.ARMED) return
        val now = android.os.SystemClock.elapsedRealtime()
        when {
            screenOnAt < screenOffAt -> scope.launch {     // screen-on broadcast still on its way
                delay(1_500)
                if (intro == Intro.ARMED) playIntro()
            }
            now - screenOnAt < 15_000 -> playIntro()       // woke up and landed (or came back) Home
            else -> intro = Intro.NONE                     // TV woke into another app a while ago
        }
    }

    /** Called for every key the activity receives. Returns true if the key should be swallowed. */
    fun onKey(keyCode: Int, down: Boolean): Boolean {
        lastInput = System.currentTimeMillis()
        if (buttonMappingCapture) {
            if (down) {
                captureButtonMapping(keyCode)
                swallowKey = keyCode
            }
            if (!down && swallowKey == keyCode) { swallowKey = null; return true }
            return true
        }
        if (down) buttonMappings.firstOrNull { it.keyCode == keyCode }?.let { mapping ->
            when {
                mapping.target == "switcher" -> openAppSwitcher()
                mapping.target.startsWith("app:") -> apps.firstOrNull { it.pkg == mapping.target.removePrefix("app:") }?.let(::launch)
            }
            return true
        }
        if (intro == Intro.ARMED) intro = Intro.NONE         // never leave a blank screen up
        if (down && intro == Intro.PLAYING) {               // any button skips the intro
            intro = Intro.NONE
            swallowKey = keyCode
            return true
        }
        if (down && ambient) {
            ambient = false
            swallowKey = keyCode
            return true
        }
        if (!down && swallowKey == keyCode) {
            swallowKey = null
            return true
        }
        return false
    }

    fun resetIdle() { lastInput = System.currentTimeMillis(); ambient = false }

    /** Music playing: ambient after 30 s idle on Home. Otherwise it only starts from the TV's screensaver or the menu. */
    fun checkIdle() {
        if (ambient || screen != Screen.HOME || sheet != null || privacySheet || musicSheet || weatherPicker ||
            settingsPage != null || vizSettingsOpen || updateDialog || watchSheet != null
        ) return
        val idle = System.currentTimeMillis() - lastInput
        val playing = nowPlaying.track?.playing == true
        if (playing && idle >= 30_000) ambient = true
    }

    fun openNowPlayingApp() {
        val pkg = nowPlaying.track?.pkg ?: return
        val pm = context.packageManager
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.let { start(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---- Tracker sign-in (Trakt, Simkl, MDBList, WeTrakr) ----------------------------------------

    /** Opens the Connect screen on the service picker. */
    fun startConnect() {
        sheet = null
        connectJob?.cancel()
        deviceCode = null
        editingClientId = false
        connectStatus = ""
        pickingService = true
        screen = Screen.CONNECT
    }

    /**
     * Picks the service and starts its sign-in. Only one is connected at a time: choosing a different one
     * signs out of the current one here (your history stays on that service).
     */
    fun chooseService(s: Service) {
        // Never sign out of the current service for one that can't sign in.
        if (!available(s)) { showToast("${s.title} isn't set up in this version of Veil yet"); return }
        if (s == service && connected) { cancelConnect(); return } // already connected: nothing to do
        if (s != service) {
            tracker.disconnect()
            service = s
            prefs.edit().putString("tracker", s.id).apply()
            connected = false
            items = emptyList()
            VeilCache.items = emptyList()
            focused = null
            focusedIndex = 0
            lastLoad = 0
        }
        pickingService = false
        beginSignIn()
    }

    private fun beginSignIn() {
        val t = tracker
        val name = t.service.title
        editingClientId = false
        deviceCode = null
        screen = Screen.CONNECT
        if (!t.hasKeys) {
            connectStatus = ""
            if (t.service == Service.TRAKT) editClientId()
            else connectStatus = "$name isn't set up in this version of Veil yet."
            return
        }
        connectStatus = "Getting a code…"
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val dc = withContext(Dispatchers.IO) { t.requestDeviceCode() }
                deviceCode = dc
                connectStatus = "Waiting for you to approve…"
                var interval = dc.interval.coerceAtLeast(1)
                val end = System.currentTimeMillis() + dc.expiresIn * 1000L
                while (System.currentTimeMillis() < end) {
                    delay(interval * 1000L)
                    when (withContext(Dispatchers.IO) { runCatching { t.pollDeviceToken(dc) }.getOrDefault(Poll.PENDING) }) {
                        Poll.AUTHORIZED -> {
                            connected = true
                            deviceCode = null
                            screen = Screen.HOME
                            refreshWatching(force = true)
                            return@launch
                        }
                        Poll.SLOW_DOWN -> interval += 5
                        Poll.FAILED -> { connectStatus = "Sign-in was denied or the code expired. Press Back and try again."; return@launch }
                        Poll.PENDING -> Unit
                    }
                }
                connectStatus = "The code expired. Press Back and try again."
            } catch (e: BadClientId) {
                if (t.service == Service.TRAKT) {
                    connectStatus = "Trakt didn't accept this client ID. Check it and enter it again."
                    editClientId()
                } else {
                    connectStatus = "$name didn't accept Veil's app ID. Please report this on GitHub."
                }
            } catch (e: Exception) {
                connectStatus = "Couldn't reach $name. Check the network and try again."
            }
        }
    }

    /** Back on the Connect screen: from a sign-in code to the picker, from the picker to Home. */
    fun connectBack() {
        if (pickingService) cancelConnect()
        else { connectJob?.cancel(); deviceCode = null; editingClientId = false; connectStatus = ""; pickingService = true }
    }

    /** Shows the client ID field (stops any sign-in in progress). */
    fun editClientId() {
        connectJob?.cancel()
        deviceCode = null
        editingClientId = true
    }

    /** Switches Trakt app (null: back to Veil's shared one), then starts sign-in with it. */
    fun saveClientId(id: String?) {
        trakt.setClientId(id)
        connected = false
        items = emptyList()
        focused = null
        focusedIndex = 0
        beginSignIn()
    }

    fun cancelConnect() {
        connectJob?.cancel()
        deviceCode = null
        editingClientId = false
        pickingService = true
        screen = Screen.HOME
    }

    fun disconnect() {
        tracker.disconnect()
        connected = false
        items = emptyList()
        focused = null
        focusedIndex = 0
    }

    // ---- Launching ----------------------------------------------------------------------------

    fun launch(app: AppEntry) {
        saveRecentAppPackage(app.pkg)
        appSwitcherOpen = false
        start(Intent(app.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openSettings() = start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openAppInfo(app: AppEntry) = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    // ---- Which app plays Continue Watching (chosen once in the Veil menu) ----

    /**
     * The apps Continue Watching can play with. Watch Next apps publish their own Continue Watching row to Android TV
     * (Veil reads it); Legacy is read through the connected tracker instead. [packages] lists every package name the
     * app is known to install as; the one actually on the TV is used.
     */
    enum class Player(val id: String, val title: String, val packages: List<String>, val usesWatchNext: Boolean) {
        NUVIO_IPTV("nuvio", "Nuvio + IPTV", listOf(NUVIO_IPTV_PKG), true),
        NUVIO_LEGACY("nuvio_legacy", "Nuvio Legacy", listOf(NUVIO_LEGACY_PKG), false),
        NUVIO_MAIN("nuvio_main", "Nuvio", listOf("com.nuvio.tv", "com.nuvio.app"), true),
        NUVIO_ARC("nuvio_arc", "Nuvio Arc", listOf("com.nuvio.tv.test"), true),
    }

    // The old "nuvio" preference intentionally maps to Nuvio + IPTV for existing installs.
    var player by mutableStateOf(Player.entries.firstOrNull { it.id == prefs.getString("player", null) } ?: Player.NUVIO_IPTV)
        private set

    /** The package name [p] is installed as on this TV, or null if it isn't installed. */
    fun installedPackage(p: Player): String? = p.packages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun isInstalled(p: Player): Boolean = installedPackage(p) != null

    fun choosePlayer(p: Player) {
        if (!isInstalled(p)) { dev.veil.launcher.ui.Sfx.error(); showToast("${p.title} isn't installed on this TV"); return }
        if (player == p) return
        player = p
        prefs.edit().putString("player", p.id).apply()
        lastLoad = 0L; lastNativeLoad = 0L
        items = emptyList(); VeilCache.items = emptyList()
        focused = null; focusedIndex = 0; error = null
        refreshWatching(force = true)
    }

    private fun effectivePlayer(): Player =
        if (isInstalled(player)) player else Player.entries.firstOrNull { isInstalled(it) } ?: player

    fun open(item: WatchItem) {
        val selected = effectivePlayer()
        val pkg = installedPackage(selected) ?: return
        if (selected.usesWatchNext) {
            if (item.isNativeWatchNext && openNativeIntent(item)) return
            openInNuvioApp(item, pkg)
        } else {
            openInNuvioLegacy(item)
        }
    }

    private fun openNativeIntent(item: WatchItem): Boolean {
        val raw = item.nativeIntentUri ?: return false
        val intent = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: runCatching { Intent(Intent.ACTION_VIEW, Uri.parse(raw)) }.getOrNull()
            ?: return false
        if (intent.`package` == null && intent.component == null) {
            item.nativePackage?.let { intent.setPackage(it) }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent)
    }

    /** Nuvio, Nuvio Arc and Nuvio + IPTV accept the same activity extras used by their Watch Next cards. */
    private fun openInNuvioApp(item: WatchItem, pkg: String) {
        val i = Intent(Intent.ACTION_MAIN).setClassName(pkg, NUVIO_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val imdb = item.imdb
        if (imdb != null) {
            i.putExtra("contentId", imdb)
            i.putExtra("contentType", if (item.isMovie) "movie" else "series")
            i.putExtra("launchMode", "stream")
            i.putExtra("videoId", if (item.isMovie) imdb else "$imdb:${item.season}:${item.episode}")
            i.putExtra("name", item.title)
            if (!item.isMovie) {
                i.putExtra("season", item.season); i.putExtra("episode", item.episode)
                item.episodeTitle?.let { i.putExtra("episodeTitle", it) }
            }
            item.poster?.let { i.putExtra("poster", it) }
            item.thumb?.let { i.putExtra("backdrop", it) }
            item.logo?.let { i.putExtra("logo", it) }
        }
        if (!start(i)) context.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let {
            start(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Nuvio Legacy's documented external deep-link interface. */
    private fun openInNuvioLegacy(item: WatchItem) {
        val id = item.imdb ?: item.tmdb?.let { "tmdb:$it" }
        val uri = id?.let {
            if (item.isMovie) "nuvio-legacy://play/movie/$it"
            else "nuvio-legacy://play/series/$it/${item.season}/${item.episode}"
        }
        val intent = uri?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(NUVIO_LEGACY_PKG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (intent == null || !start(intent)) {
            context.packageManager.getLeanbackLaunchIntentForPackage(NUVIO_LEGACY_PKG)?.let {
                start(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent); true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    companion object {
        const val NUVIO_IPTV_PKG = "com.nuvio.livetv"
        const val NUVIO_LEGACY_PKG = "space.nuvio.nativelegacy"
        const val NUVIO_ACTIVITY = "com.nuvio.tv.MainActivity"
        val DEFAULT_PINS = listOf(
            "io.gh.reisxd.tizentube.cobalt",
            NUVIO_IPTV_PKG,
            "com.spotify.tv.android",
        )
    }
}
