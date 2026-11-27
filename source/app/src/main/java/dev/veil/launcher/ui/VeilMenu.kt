@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.veil.launcher.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.veil.launcher.LauncherState
import dev.veil.launcher.data.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The "veil" wordmark is a button. OK drops the Veil menu down under it (start Veil Ambient, pick a theme,
 * change the weather location) and moves focus to its first item; OK again closes it. It also closes when
 * focus leaves the button and the menu.
 */
@Composable
fun VeilMenuAnchor(state: LauncherState) {
    val firstItem = remember { FocusRequester() }
    // focusGroup: the button and its menu share one focus parent, so moving focus between them never
    // reads as "focus left" for a moment (which closed the menu right after OK opened it).
    Box(Modifier.onFocusChanged {
        state.wordmarkFocused = it.hasFocus
        if (!it.hasFocus) state.veilMenuOpen = false
    }.focusGroup()) {
        // Styled like the other top-bar pills (soft fill + outline + chevron) so it reads as a button.
        Surface(
            onClick = {
                if (state.veilMenuOpen) { Sfx.back(); state.veilMenuOpen = false }
                else { Sfx.open(); state.veilMenuOpen = true; state.veilMenuFocusTick++ }
            },
            // Back from the "veil" button closes the menu if it's open, otherwise returns to the page.
            modifier = Modifier.focusSound(1.12f).onPreviewKeyEvent { e ->
                if (e.key == Key.Back && e.type == KeyEventType.KeyUp) {
                    Sfx.back()
                    if (state.veilMenuOpen) state.veilMenuOpen = false else state.focusHome()
                    true
                } else e.key == Key.Back
            },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Veil.Glass, contentColor = Veil.Text,
                focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Veil.Line), shape = RoundedCornerShape(50)),
                focusedBorder = Border.None,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Row(Modifier.padding(start = 9.dp, end = 9.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                // December: a snowflake instead of the lock (if seasonal touches are on).
                Icon(
                    if (state.seasonal && Seasons.isWinter()) VeilIcons.Snowflake else VeilIcons.Lock,
                    contentDescription = null, modifier = Modifier.size(13.dp), tint = LocalContentColor.current,
                )
                Spacer(Modifier.width(6.dp))
                Text("veil", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
                Spacer(Modifier.width(5.dp))
                Icon(
                    VeilIcons.ChevronDown, contentDescription = "Menu", modifier = Modifier.size(11.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
                // A newer Veil is on GitHub: a small accent dot, like an unread badge. Details in the menu.
                if (state.availableUpdate != null) {
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Veil.Accent))
                }
            }
        }
        Box(Modifier.floatingBelow(40.dp)) {
            // Kept built while on Home (it was rebuilt from scratch on every open, which stuttered); while
            // closed it isn't placed on screen, so the remote can never move focus into it.
            KeptPanel(open = state.veilMenuOpen) { VeilPanel(state, firstItem) }
        }
    }
}

/**
 * Shows [content] when [open], fading and sliding it in/out (no offscreen copy). The content stays composed
 * while closed, so opening is instant; closed, it's measured but not placed, which also keeps it out of
 * D-pad focus search.
 */
@Composable
private fun KeptPanel(open: Boolean, content: @Composable () -> Unit) {
    val progress = androidx.compose.animation.core.animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 200 else 120), label = "veil-panel",
    )
    val placed by remember { androidx.compose.runtime.derivedStateOf { progress.value > 0f } }
    val show = open || placed
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            translationY = -24.dp.toPx() * (1f - p)
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w, h) { if (show) placeables.forEach { it.place(0, 0) } }
    }
}

@Composable
private fun VeilPanel(state: LauncherState, firstItem: FocusRequester) {
    var focusedTheme by remember { mutableStateOf<VeilThemeSpec?>(null) }
    // Opened with OK: move focus to the first item once the panel is placed (it's placed the frame it opens).
    LaunchedEffect(state.veilMenuFocusTick) {
        if (state.veilMenuFocusTick > 0 && state.veilMenuOpen) {
            withFrameNanos { }
            runCatching { firstItem.requestFocus() }
        }
    }
    val current = ThemeState.current
    val ambientButton = remember { FocusRequester() }
    val tmdbButton = remember { FocusRequester() }
    val playerButton = remember { FocusRequester() }
    val weatherButton = remember { FocusRequester() }
    val updateButton = remember { FocusRequester() }
    // The panel stays built between opens; start each open scrolled to the top, as before.
    val scroll = rememberScrollState()
    LaunchedEffect(state.veilMenuOpen) { if (!state.veilMenuOpen) scroll.scrollTo(0) }
    // After a window opened from this menu closes, put focus back on the button that opened it.
    LaunchedEffect(state.menuReturn) {
        val target = when (state.menuReturn) {
            "tmdb" -> tmdbButton
            "player" -> playerButton
            "ambient" -> ambientButton
            "weather" -> weatherButton
            "update" -> updateButton
            // "Hide until the next update": move focus off the row first, then remove it. Removing the
            // focused row first would leave the menu without focus, which closes it.
            "update-hide" -> firstItem
            else -> null
        }
        if (target != null) {
            delay(80)
            runCatching { target.requestFocus() }
            if (state.menuReturn == "update-hide") { withFrameNanos { }; state.hideUpdate() }
            state.menuReturn = null
        }
    }
    Column(
        Modifier
            .onPreviewKeyEvent { e ->
                // Back closes the menu by sending focus back to the page.
                if (e.key == Key.Back && e.type == KeyEventType.KeyUp) { Sfx.back(); state.focusHome(); true }
                else e.key == Key.Back
            }
            .width(330.dp).heightIn(max = 445.dp).clip(RoundedCornerShape(20.dp)).background(Veil.Sheet)
            .border(1.dp, Veil.Line, RoundedCornerShape(20.dp))
            .verticalScroll(scroll) // focus moving down scrolls it into view
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("veil", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
            Text("Private by design · no ads · no tracking", fontSize = 9.sp, color = Veil.Text.copy(alpha = 0.6f))
        }

        // A newer Veil on GitHub: a short row; OK opens the details window, where you decide.
        state.availableUpdate?.let { u ->
            MenuButton(
                icon = VeilIcons.Sparkle,
                title = "Update available",
                subtitle = "${ago(u.time).replaceFirstChar { it.uppercase() }} · OK for details",
                modifier = Modifier.focusRequester(updateButton),
                onClick = { state.updateDialog = true },
            )
        }

        MenuButton(
            icon = VeilIcons.Grid,
            title = "Button Mapping",
            modifier = Modifier.focusRequester(firstItem),
            subtitle = "Map remote buttons to Recent Apps or an installed app",
            onClick = { state.buttonMappingOpen = true },
        )

        MenuButton(
            icon = VeilIcons.Play, title = "Play shows with", subtitle = state.player.title,
            modifier = Modifier.focusRequester(playerButton),
            onClick = { state.settingsPage = LauncherState.SettingsPage.PLAYER },
        )

        // Themes
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shown = focusedTheme ?: current
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("THEME")
                Spacer(Modifier.width(8.dp))
                Text(shown.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = shown.accent)
                if (shown.id == current.id) {
                    Spacer(Modifier.width(5.dp))
                    Text("· active", fontSize = 9.sp, color = Veil.Text.copy(alpha = 0.5f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Themes.all.forEach { t ->
                    ThemeBubble(
                        theme = t,
                        selected = t.id == current.id,
                        onClick = { state.setTheme(t) },
                        onFocus = { focusedTheme = if (it) t else if (focusedTheme == t) null else focusedTheme },
                    )
                }
            }
            MenuButton(
                icon = VeilIcons.Sparkle,
                title = "Colours from artwork: " + if (state.artColors) "on" else "off",
                subtitle = "The accent glows in the colours of the show you're on",
                onClick = state::toggleArtColors,
            )
        }

        // Weather location
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("WEATHER")
            val w = state.weather
            MenuButton(
                icon = if (w != null) weatherIcon(w) else VeilIcons.Pin2,
                title = state.weatherPlace ?: "No location set",
                subtitle = (w?.let { "${it.tempC}° now · " } ?: "") + "Change location",
                modifier = Modifier.focusRequester(weatherButton),
                onClick = { state.weatherPicker = true },
            )
        }

        // Seasonal touches
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("SOUNDS & SEASONAL")
            MenuButton(
                icon = VeilIcons.Note,
                title = "Interface sounds: " + if (state.sounds) "on" else "off",
                subtitle = "Soft ticks and chimes as you move around",
                onClick = state::toggleSounds,
            )
            MenuButton(
                icon = if (Seasons.isWinter()) VeilIcons.Snowflake else VeilIcons.Sparkle,
                title = "Seasonal touches: " + if (state.seasonal) "on" else "off",
                subtitle = "Snow in December, New Year countdown… in Ambient",
                onClick = state::toggleSeasonal,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("APPEARANCE & ARTWORK")
            MenuButton(
                icon = VeilIcons.Sparkle, title = "Veil Ambient",
                subtitle = "Screensaver, backgrounds & music mode",
                modifier = Modifier.focusRequester(ambientButton),
                onClick = { state.settingsPage = LauncherState.SettingsPage.AMBIENT },
            )
            MenuButton(
                icon = VeilIcons.Play, title = "TMDB API key",
                subtitle = if (state.tmdb.enabled) "Configured" else "Add a key for artwork",
                modifier = Modifier.focusRequester(tmdbButton),
                onClick = { state.settingsPage = LauncherState.SettingsPage.TMDB },
            )
        }

        // Update check (one small request a day to GitHub's public API)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("UPDATES")
            MenuButton(
                icon = VeilIcons.Sparkle,
                title = "Check for updates: " + if (state.updateCheck) "on" else "off",
                subtitle = "Once a day, asks GitHub if a newer Veil is out. Nothing about you is sent",
                onClick = state::toggleUpdateCheck,
            )
        }

        Text("Back to close", fontSize = 8.5.sp, color = Veil.Text.copy(alpha = 0.45f))
    }
}

private const val TV_LISTINGS_PERMISSION = "android.permission.READ_TV_LISTINGS"

@Composable
private fun SettingsDialog(title: String, message: String?, close: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = close) {
        Column(
            Modifier.width(440.dp).heightIn(max = 480.dp).clip(RoundedCornerShape(22.dp))
                .background(Veil.Sheet).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Veil.Text)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
            if (message != null) {
                Text(message, fontSize = 13.sp, color = Veil.Text)
            }
        }
    }
}

private fun closeSettingsPage(state: LauncherState, returnTo: String) {
    state.settingsPage = null
    state.veilMenuOpen = true
    state.menuReturn = returnTo
}

@Composable
internal fun TmdbSettingsDialog(state: LauncherState) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val close = { closeSettingsPage(state, "tmdb") }
    val field = remember { FocusRequester() }
    SettingsDialog("TMDB API key", error, close) {
        Text("Enter a TMDB v3 API key or API Read Access Token. Veil saves it on this device.",
            fontSize = 13.sp, color = Veil.Text)
        BasicTextField(
            value = input,
            onValueChange = { input = it; error = null },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            ),
            textStyle = TextStyle(color = Veil.Text, fontSize = 14.sp),
            cursorBrush = SolidColor(Veil.Accent),
            modifier = Modifier.fillMaxWidth().focusRequester(field).background(Veil.Glass)
                .padding(12.dp),
            decorationBox = { inner ->
                Box {
                    if (input.isEmpty()) Text("Enter key", color = Veil.Text.copy(alpha = 0.65f), fontSize = 14.sp)
                    inner()
                }
            },
        )
        MenuButton(VeilIcons.Play, "Save", "Use this key for artwork on this device") {
            val key = input.trim()
            if (key.matches(Regex("[a-fA-F0-9]{32}")) ||
                key.matches(Regex("eyJ[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+[.][A-Za-z0-9_-]+"))) {
                state.saveTmdbKey(key)
                close()
            } else {
                error = "Enter a 32-character API key or a complete API Read Access Token."
            }
        }
        if (state.tmdb.enabled) {
            MenuButton(VeilIcons.Unpin, "Remove key", "Disable TMDB artwork on this device") {
                state.saveTmdbKey("")
                close()
            }
        }
        LaunchedEffect(Unit) { field.requestFocus() }
    }
}

@Composable
internal fun PlayerSettingsDialog(state: LauncherState) {
    val context = LocalContext.current
    val activeButton = remember { FocusRequester() }
    val close = { closeSettingsPage(state, "player") }
    var permissionHint by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            state.startWatchNextUpdates()
            state.refreshWatching(force = true)
            close()
        } else {
            permissionHint = true
        }
    }
    SettingsDialog("Play shows with", state.toast, close) {
        LauncherState.Player.entries.forEach { player ->
            val installed = state.isInstalled(player)
            val selected = state.player == player
            MenuButton(
                icon = VeilIcons.Play, title = player.title,
                subtitle = when {
                    !installed && selected -> "Selected · Not installed"
                    !installed -> "Not installed"
                    selected -> "Selected"
                    else -> "Select"
                },
                modifier = if (selected) Modifier.focusRequester(activeButton) else Modifier,
                onClick = {
                    state.choosePlayer(player)
                    if (installed) {
                        if (player.usesWatchNext &&
                            context.checkSelfPermission(TV_LISTINGS_PERMISSION) != PackageManager.PERMISSION_GRANTED
                        ) {
                            request.launch(TV_LISTINGS_PERMISSION)
                        } else {
                            close()
                        }
                    }
                },
            )
        }
        if (permissionHint) {
            Text("Allow TV listings access to show Continue Watching. If your TV skips the prompt, grant READ_TV_LISTINGS to dev.veil.launcher using ADB.",
                fontSize = 13.sp, color = Veil.Text)
        }
        LaunchedEffect(Unit) { activeButton.requestFocus() }
    }
}

@Composable
internal fun AmbientSettingsDialog(state: LauncherState) {
    val first = remember { FocusRequester() }
    val vizButton = remember { FocusRequester() }
    val close = { closeSettingsPage(state, "ambient") }
    SettingsDialog("Veil Ambient", state.toast, close) {
        MenuButton(
            icon = VeilIcons.Play, title = "Start now", subtitle = "Show Veil Ambient right away",
            modifier = Modifier.focusRequester(first),
            onClick = { close(); state.startAmbient() },
        )
        MenuButton(
            icon = VeilIcons.Sparkle,
            title = "TV screensaver: " + if (state.screensaverIsVeil) "Veil Ambient" else "TV's own",
            subtitle = "Veil Ambient as the screensaver in any app. Off puts the TV's own screensaver back",
            onClick = state::toggleScreensaver,
        )
        if (state.screensaverIsVeil) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { minutes ->
                    ModeChip(
                        "$minutes min", VeilIcons.Sparkle, state.screensaverMinutes == minutes, Modifier.weight(1f),
                    ) { state.chooseScreensaverMinutes(minutes) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("TRENDING PERIOD")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Daily", VeilIcons.Sparkle, state.ambientTrendingWindow == "day", Modifier.weight(1f)) {
                    state.chooseAmbientTrendingWindow("day")
                }
                ModeChip("Weekly", VeilIcons.Sparkle, state.ambientTrendingWindow == "week", Modifier.weight(1f)) {
                    state.chooseAmbientTrendingWindow("week")
                }
            }
            SectionLabel("MOVIE BACKGROUNDS")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 3, 5, 10).forEach { count ->
                    ModeChip("$count", VeilIcons.Star, state.ambientMovieCount == count, Modifier.weight(1f)) {
                        state.chooseAmbientMovieCount(count)
                    }
                }
            }
            SectionLabel("TV SHOW BACKGROUNDS")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 3, 5, 10).forEach { count ->
                    ModeChip("$count", VeilIcons.Play, state.ambientShowCount == count, Modifier.weight(1f)) {
                        state.chooseAmbientShowCount(count)
                    }
                }
            }
            SectionLabel("CHANGE BACKGROUND EVERY")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 14, 30, 60).forEach { seconds ->
                    ModeChip(
                        "${seconds}s", VeilIcons.Timer, state.ambientSwitchSeconds == seconds, Modifier.weight(1f),
                    ) { state.chooseAmbientSwitchSeconds(seconds) }
                }
            }
        }
        // Music mode: what Veil Ambient shows while music plays
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("MUSIC MODE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Veil", VeilIcons.Note, state.musicStyle == "veil", Modifier.weight(1f)) { state.chooseMusicStyle("veil") }
                ModeChip(
                    when {
                        state.visualizerAllowed -> "Visualizer"
                        !state.visualizerSupported -> "Visualizer (not supported on this TV)"
                        else -> "Visualizer (no audio access)"
                    },
                    VeilIcons.Wave, state.musicStyle == "viz", Modifier.weight(1f),
                ) { state.chooseMusicStyle("viz") }
            }
            if (state.musicStyle == "veil") {
                MenuButton(
                    icon = VeilIcons.Lyrics,
                    title = "Lyrics: " + if (state.lyricsMusic) "on" else "off",
                    subtitle = "Synced lyrics next to the album art",
                    onClick = state::toggleLyricsMusic,
                )
            }
            if (state.musicStyle == "viz" && state.visualizerAllowed) {
                MenuButton(
                    icon = VeilIcons.Wave, title = "Visualizer settings", subtitle = "Sensitivity, motion, trails… with live preview",
                    modifier = Modifier.focusRequester(vizButton),
                    onClick = { state.vizSettingsOpen = true },
                )
            }
        }
        MenuButton(icon = VeilIcons.Unpin, title = "Done", subtitle = "Return to settings", onClick = close)
        LaunchedEffect(Unit) {
            if (state.menuReturn == "viz" && state.musicStyle == "viz" && state.visualizerAllowed) {
                vizButton.requestFocus()
                state.menuReturn = null
            } else {
                first.requestFocus()
            }
        }
    }
}

/** "today", "yesterday", "3 days ago", "2 weeks ago". */
private fun ago(time: Long): String {
    val days = ((System.currentTimeMillis() - time) / 86_400_000L).coerceAtLeast(0)
    return when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 14 -> "$days days ago"
        else -> "${days / 7} weeks ago"
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Veil.Text.copy(alpha = 0.55f))

@Composable
internal fun MenuButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { Sfx.select(); onClick() },
        modifier = modifier.fillMaxWidth().focusSound(1.12f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f), contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val onWhite = LocalContentColor.current == Veil.Ink
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (onWhite) Veil.Ink.copy(alpha = 0.08f) else Veil.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (onWhite) Veil.Ink else Veil.Accent)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 9.sp, color = dimColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One option of a two-way switch: selected = accent outline + check. */
@Composable
private fun ModeChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = { Sfx.select(); onClick() },
        modifier = modifier.focusSound(1.12f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Veil.Accent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
            contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(1.5.dp, Veil.Accent), shape = RoundedCornerShape(50)) else Border.None,
            focusedBorder = Border.None,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val onWhite = LocalContentColor.current == Veil.Ink
            Icon(
                if (selected) VeilIcons.Check else icon, contentDescription = null, modifier = Modifier.size(12.dp),
                tint = if (onWhite) Veil.Ink else if (selected) Veil.Accent else Veil.Text,
            )
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A round theme preview: the theme's background, an accent glow, and its glyph. */
@Composable
private fun ThemeBubble(theme: VeilThemeSpec, selected: Boolean, onClick: () -> Unit, onFocus: (Boolean) -> Unit) {
    Surface(
        onClick = { Sfx.select(); onClick() },
        modifier = Modifier.size(32.dp).onFocusChanged { onFocus(it.isFocused) }.focusSound(1.25f),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = theme.bg, focusedContainerColor = theme.bg),
        border = ClickableSurfaceDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, theme.accent), shape = CircleShape)
            else Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), shape = CircleShape),
            focusedBorder = Border(BorderStroke(2.5.dp, Color.White), shape = CircleShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.22f),
    ) {
        Box(
            Modifier.size(32.dp).background(
                Brush.radialGradient(listOf(theme.accent.copy(alpha = 0.45f), Color.Transparent), radius = 40f),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(theme.glyph(), contentDescription = theme.name, modifier = Modifier.size(14.dp), tint = theme.accent)
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(theme.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(VeilIcons.Check, contentDescription = "Active", modifier = Modifier.size(8.dp), tint = Veil.Ink)
            }
        }
    }
}

/** Details of a newer Veil, and the choice: remind me later, or hide until the next update. */
@Composable
fun UpdateDialog(state: LauncherState) {
    val u = state.availableUpdate
    val later = remember { FocusRequester() }
    val close = { state.updateDialog = false; state.menuReturn = "update" }
    if (u == null) { LaunchedEffect(Unit) { close() }; return }
    val yours = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
        .format(java.time.Instant.ofEpochSecond(dev.veil.launcher.BuildConfig.SOURCE_TIME).atZone(java.time.ZoneId.systemDefault()))
    Dialog(onDismissRequest = { Sfx.back(); close() }) {
        Column(
            Modifier.width(400.dp).clip(RoundedCornerShape(20.dp)).background(Veil.Sheet)
                .border(1.dp, Veil.Line, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("UPDATE AVAILABLE", fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Veil.Accent)
            Text("A newer Veil is out", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            Text(
                "Published ${ago(u.time)}. Yours is from $yours.",
                fontSize = 10.sp, color = Veil.Text.copy(alpha = 0.6f),
            )
            if (u.title.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                SectionLabel("WHAT'S NEW")
                Text(u.title, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.height(2.dp))
            SectionLabel("HOW TO UPDATE")
            Text(
                "Ask your AI agent to \"update Veil\" on the computer you installed it from. It follows the " +
                    "Updating Veil steps in AGENT_INSTALL.md. Your settings and sign-in stay as they are.",
                fontSize = 11.sp, lineHeight = 16.sp, color = Veil.Text.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(
                    onClick = { close() }, padding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                    modifier = Modifier.focusRequester(later),
                ) { Text("Remind me later", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
                Pill(
                    // The menu hides it once focus has moved off the row (see VeilPanel).
                    onClick = { state.updateDialog = false; state.menuReturn = "update-hide" },
                    padding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                ) { Text("Hide until the next update", fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
            }
            Text("Back to close", fontSize = 8.5.sp, color = Veil.Text.copy(alpha = 0.45f))
        }
    }
    LaunchedEffect(Unit) { delay(120); runCatching { later.requestFocus() } }
}

/** Weather location search: the TV keyboard (with voice) opens right away; results update as you type. */
@Composable
fun WeatherPicker(state: LauncherState) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Weather.Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val close = { state.weatherPicker = false; state.menuReturn = "weather" }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        results = withContext(Dispatchers.IO) { Weather.search(query) }
        searching = false
    }

    Dialog(onDismissRequest = { Sfx.back(); close() }) {
        Column(
            Modifier.width(320.dp).clip(RoundedCornerShape(20.dp)).background(Veil.Sheet)
                .border(1.dp, Veil.Line, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Weather location", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text("Now: ${state.weatherPlace ?: "not set"}", fontSize = 9.5.sp, color = Veil.Text.copy(alpha = 0.6f))
            Spacer(Modifier.height(2.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Veil.Text, fontSize = 13.sp, fontFamily = Manrope),
                cursorBrush = SolidColor(Veil.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(field)
                    .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Veil.Line, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(VeilIcons.Search, contentDescription = null, modifier = Modifier.size(13.dp), tint = Veil.Text.copy(alpha = 0.6f))
                        Spacer(Modifier.width(8.dp))
                        Box {
                            if (query.isEmpty()) Text("Type or say a city…", fontSize = 13.sp, color = Veil.Text.copy(alpha = 0.45f))
                            inner()
                        }
                    }
                },
            )
            if (searching) Text("Searching…", fontSize = 9.5.sp, color = Veil.Text.copy(alpha = 0.6f))
            results.forEach { p ->
                PickerRow(title = p.name, subtitle = p.region) { state.setWeatherPlace(p); close() }
            }
            if (state.weatherHasOverride && state.weatherDefault.isNotEmpty()) {
                PickerRow(title = "Use default", subtitle = state.weatherDefault) { state.resetWeatherPlace(); close() }
            }
            Text("Back to cancel", fontSize = 8.5.sp, color = Veil.Text.copy(alpha = 0.45f))
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { field.requestFocus() }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = { Sfx.select(); onClick() },
        modifier = Modifier.fillMaxWidth().focusSound(1.12f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(VeilIcons.Pin2, contentDescription = null, modifier = Modifier.size(12.dp), tint = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(subtitle, fontSize = 9.5.sp, color = dimColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
