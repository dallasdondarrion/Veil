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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle

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
    if (state.appSwitcherOpen) AppSwitcherDialog(state)
    when (state.settingsPage) {
        LauncherState.SettingsPage.TMDB -> TmdbSettingsDialog(state)
        LauncherState.SettingsPage.PLAYER -> PlayerSettingsDialog(state)
        LauncherState.SettingsPage.AMBIENT -> if (!state.vizSettingsOpen) AmbientSettingsDialog(state)
        null -> Unit
    }
    if (state.buttonMappingOpen) ButtonMappingDialog(state)
    if (state.privacySheet) PrivacyMenu(state)
    if (state.musicSheet) MusicMenu(state)
    state.watchSheet?.let { WatchMenu(state, it) }

    // Short confirmation ("Marked … as watched"), bottom centre
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = state.toast != null && (state.settingsPage == null || state.vizSettingsOpen),
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

@Composable
private fun AppSwitcherDialog(state: LauncherState) {
    Dialog(onDismissRequest = state::closeAppSwitcher) {
        Column(
            Modifier.clip(RoundedCornerShape(22.dp)).background(Veil.Sheet).padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text("Recent apps", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Veil.Text)
            Text("Choose an app to return to it", fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            val recent = state.recentApps
            if (recent.isEmpty()) {
                Text("Recently used apps will appear here.", fontSize = 14.sp, color = Veil.Text.copy(alpha = 0.8f))
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    recent.forEachIndexed { index, app ->
                        if (index > 0) Spacer(Modifier.width(8.dp))
                        AppPill(
                            app = app,
                            onClick = { state.launch(app) },
                            onLongClick = null,
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainerColor = Veil.GlassStrong,
                            focusedContentColor = Veil.Text,
                            modifier = Modifier.onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                                    state.dismissRecentApp(app.pkg)
                                    true
                                } else false
                            },
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun ButtonMappingDialog(state: LauncherState) {
    Dialog(onDismissRequest = state::closeButtonMapping) {
        Column(
            Modifier.widthIn(max = 620.dp).heightIn(max = 500.dp).clip(RoundedCornerShape(22.dp))
                .background(Veil.Sheet).verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Text("Button Mapping", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Veil.Text)
            Text(
                "Assign a remote shortcut to Recent Apps or any installed app. The Home helper must be enabled for mappings to work outside Veil.",
                fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.72f), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            when {
                state.buttonMappingCapture -> {
                    Text("Press the remote button you want to map.", fontSize = 16.sp, color = Veil.Accent)
                    MenuButton(icon = VeilIcons.Unpin, title = "Cancel mapping", subtitle = "Return to the mappings list", onClick = state::cancelButtonMappingCapture)
                }
                state.buttonMappingChoosingTarget -> {
                    MenuButton(
                        icon = VeilIcons.Grid, title = "Recent Apps", subtitle = "Open Veil’s recent apps row",
                        onClick = { state.completeButtonMapping("switcher") },
                    )
                    Text("INSTALLED APPS", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Veil.Text.copy(alpha = 0.55f), modifier = Modifier.padding(top = 12.dp, bottom = 5.dp))
                    state.apps.forEach { app ->
                        MenuButton(
                            icon = VeilIcons.Play, title = app.label, subtitle = "Press a remote button to map this app",
                            onClick = { state.completeButtonMapping("app:${app.pkg}") },
                        )
                    }
                    MenuButton(icon = VeilIcons.Unpin, title = "Back", subtitle = "Return to mapped buttons", onClick = state::cancelButtonMappingCapture)
                }
                else -> {
                    if (state.buttonMappings.isEmpty()) {
                        Text("No buttons mapped yet.", fontSize = 14.sp, color = Veil.Text.copy(alpha = 0.75f), modifier = Modifier.padding(bottom = 10.dp))
                    } else {
                        state.buttonMappings.forEach { mapping ->
                            MenuButton(
                                icon = VeilIcons.Unpin,
                                title = "${android.view.KeyEvent.keyCodeToString(mapping.keyCode).removePrefix("KEYCODE_")}  →  ${state.mappingTargetLabel(mapping.target)}",
                                subtitle = "Select to remove this mapping",
                                onClick = { state.removeButtonMapping(mapping.keyCode) },
                            )
                        }
                    }
                    MenuButton(icon = VeilIcons.Grid, title = "Add button mapping", subtitle = "Press a remote button, then choose what it opens", onClick = state::beginButtonMappingCapture)
                    MenuButton(icon = VeilIcons.Unpin, title = "Done", subtitle = "Close Button Mapping", onClick = state::closeButtonMapping)
                }
            }
        }
    }
}
