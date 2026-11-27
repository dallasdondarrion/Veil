package dev.veil.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.veil.launcher.data.WatchItem
import dev.veil.launcher.AppSheet
import dev.veil.launcher.LauncherState
import dev.veil.launcher.Screen

@Composable
fun VeilApp(state: LauncherState) {
    // A launcher must never "exit" on Back; sub-screens go back to Home.
    BackHandler(enabled = true) {
        when (state.screen) {
            Screen.HOME -> Unit
            Screen.DRAWER -> { Sfx.back(); state.screen = Screen.HOME }
            Screen.CONNECT -> { Sfx.back(); state.connectBack() }
        }
    }
    // Once Ambient fully covers the screen, stop drawing the page underneath it.
    val covered by androidx.compose.runtime.produceState(false, state.ambient) {
        value = false
        if (state.ambient) { kotlinx.coroutines.delay(1_000); value = true }
    }
    Box(Modifier.fillMaxSize().background(Veil.Bg).graphicsLayer { alpha = if (covered) 0f else 1f }) {
        AnimatedContent(
            targetState = state.screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { screen ->
            when (screen) {
                Screen.HOME -> HomeScreen(state)
                Screen.DRAWER -> DrawerScreen(state)
                Screen.CONNECT -> ConnectScreen(state)
            }
        }
    }
    state.sheet?.let { AppMenu(state, it) }
    if (state.privacySheet) PrivacyMenu(state)
    if (state.musicSheet) MusicMenu(state)
    state.watchSheet?.let { WatchMenu(state, it) }

    // Short confirmation ("Marked … as watched"), bottom centre
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = state.toast != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { it / 2 },
            exit = fadeOut(tween(300)),
        ) {
            Text(
                state.toast ?: "", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Veil.Ink,
                modifier = Modifier.padding(bottom = 30.dp).clip(RoundedCornerShape(50)).background(Veil.Text)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
    if (state.weatherPicker) WeatherPicker(state)
    if (state.vizSettingsOpen) VizSettingsDialog(state)
    if (state.updateDialog) UpdateDialog(state)

    // Veil Ambient sits above everything; any button press (handled in MainActivity) dismisses it.
    AnimatedVisibility(visible = state.ambient, enter = fadeIn(tween(900)), exit = fadeOut(tween(350))) {
        AmbientScreen(
            track = state.nowPlaying.track,
            items = state.items,
            weather = state.weather,
            blocked = state.blockedToday,
            dnsOn = state.dnsOn,
            visualizer = state.musicStyle == "viz" && state.visualizerAllowed,
            weeklyBlocked = state.dnsWeekly?.blocked,
            seasonal = state.seasonal,
            lyrics = state.lyricsMusic,
            trendingWindow = state.ambientTrendingWindow,
            trendingMovieCount = state.ambientMovieCount,
            trendingShowCount = state.ambientShowCount,
            slideIntervalSeconds = state.ambientSwitchSeconds,
        )
    }

    // Wake-up intro, above everything
    if (state.intro != LauncherState.Intro.NONE) {
        IntroOverlay(running = state.intro == LauncherState.Intro.PLAYING) {
            state.intro = LauncherState.Intro.NONE
        }
    }
}

@Composable
private fun PrivacyMenu(state: LauncherState) {
    val actions = buildList {
        when {
            state.dnsOn -> {
                add(SheetAction("Pause 15 minutes", VeilIcons.Timer) { state.pauseDns(15) })
                add(SheetAction("Pause 1 hour", VeilIcons.Timer) { state.pauseDns(60) })
                add(SheetAction("Turn off", VeilIcons.ShieldOff) { state.turnDnsOff() })
            }
            state.dnsPaused -> {
                add(SheetAction("Resume now", VeilIcons.ShieldOn) { state.turnDnsOn() })
                add(SheetAction("Pause 1 hour from now", VeilIcons.Timer) { state.pauseDns(60) })
                add(SheetAction("Turn off", VeilIcons.ShieldOff) { state.turnDnsOff() })
            }
            else -> add(SheetAction("Turn on", VeilIcons.ShieldOn) { state.turnDnsOn() })
        }
    }
    ActionSheet(title = "Ad blocking · ${state.dnsProvider}", actions = actions, onDismiss = { state.privacySheet = false })
}

@Composable
private fun WatchMenu(state: LauncherState, item: WatchItem) {
    val actions = listOf(
        SheetAction("Play", VeilIcons.Play) { state.open(item) },
        SheetAction("Mark as watched", VeilIcons.Check) { state.markWatched(item) },
        SheetAction("Remove from Continue Watching", VeilIcons.Unpin) { state.removeFromRow(item) },
    )
    val title = if (item.isMovie) item.title else "${item.title} · S${item.season} E${item.episode}"
    ActionSheet(title = title, actions = actions, onDismiss = { state.watchSheet = null })
}

@Composable
private fun MusicMenu(state: LauncherState) {
    val track = state.nowPlaying.track
    val appName = track?.let { t -> state.apps.firstOrNull { it.pkg == t.pkg }?.label } ?: "app"
    val actions = listOf(
        SheetAction("Open $appName", VeilIcons.Play) { state.openNowPlayingApp() },
        SheetAction("Veil Ambient", VeilIcons.Sparkle) { state.startAmbient() },
    )
    ActionSheet(title = track?.title ?: "Now playing", actions = actions, onDismiss = { state.musicSheet = false })
}

@Composable
private fun AppMenu(state: LauncherState, sheet: AppSheet) {
    val app = sheet.app
    val actions = buildList {
        if (sheet.fromHome) {
            val pinned = state.pinnedApps
            val i = pinned.indexOfFirst { it.pkg == app.pkg }
            add(SheetAction("Open", VeilIcons.Play) { state.launch(app) })
            if (i > 0) add(SheetAction("Move left", VeilIcons.Left) { state.move(app, -1) })
            if (i in 0 until pinned.lastIndex) add(SheetAction("Move right", VeilIcons.Right) { state.move(app, +1) })
            add(SheetAction("Unpin from Home", VeilIcons.Unpin) { state.unpin(app) })
            add(SheetAction("App info", VeilIcons.Info) { state.openAppInfo(app) })
            if (state.isHiddenFromAllApps(app)) {
                add(SheetAction("Show in All Apps", VeilIcons.Grid) { state.showInAllApps(app) })
            }
        } else {
            if (state.isPinned(app)) add(SheetAction("Unpin from Home", VeilIcons.Unpin) { state.unpin(app) })
            else add(SheetAction("Pin to Home", VeilIcons.Pin) { state.pin(app) })
            add(SheetAction("Open", VeilIcons.Play) { state.launch(app) })
            add(SheetAction("App info", VeilIcons.Info) { state.openAppInfo(app) })
            if (state.isHiddenFromAllApps(app)) {
                add(SheetAction("Show in All Apps", VeilIcons.Grid) { state.showInAllApps(app) })
            } else {
                add(SheetAction("Hide from All Apps", VeilIcons.Unpin) { state.hideFromAllApps(app) })
            }
        }
    }
    ActionSheet(title = app.label, actions = actions, onDismiss = { state.sheet = null })
}
