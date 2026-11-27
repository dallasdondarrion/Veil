@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.veil.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.veil.launcher.AppSheet
import dev.veil.launcher.LauncherState
import dev.veil.launcher.data.AppEntry
import kotlinx.coroutines.delay

private val TileShape = RoundedCornerShape(18.dp)

@Composable
fun DrawerScreen(state: LauncherState) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(Veil.Bg).padding(start = 48.dp, end = 48.dp, top = 26.dp)) {
        TopBar(state, Modifier.zIndex(1f))
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("All apps", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
            Spacer(Modifier.weight(1f))
            Pill(
                onClick = state::toggleRevealHiddenApps,
                padding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    if (state.revealHiddenApps) "Hide hidden apps" else "Show hidden apps",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Pill(
                onClick = { if (state.connected) state.disconnect() else state.startConnect() },
                padding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    if (state.connected) "${state.service.title} connected · Disconnect" else "Connect a tracker",
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 40.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.drawerApps, key = { _, a -> a.pkg }) { i, app ->
                AppTile(
                    app = app,
                    pinned = state.isPinned(app),
                    onClick = { state.launch(app) },
                    onLongClick = { state.sheet = AppSheet(app, fromHome = false) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, pinned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val glass = rememberGlassMotion()
    Card(
        onClick = { Sfx.select(); onClick() },
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).focusSound().glassTilt(glass),
        shape = CardDefaults.shape(TileShape),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, Veil.Text), shape = TileShape)),
        colors = CardDefaults.colors(containerColor = Color(0xFF22252C), focusedContainerColor = Color(0xFF2C3038)),
    ) {
        Box(Modifier.fillMaxSize().glassGlint(glass)) {
            val banner = app.banner
            if (banner != null) {
                Image(banner, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(34.dp).clip(CircleShape)) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        app.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            if (pinned) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(7.dp).size(20.dp).clip(CircleShape).background(Veil.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(VeilIcons.Pin, contentDescription = "Pinned", modifier = Modifier.size(11.dp), tint = Veil.Ink)
                }
            }
        }
    }
}
