package com.yichincho.englishbbc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichincho.englishbbc.data.LibraryItem
import com.yichincho.englishbbc.data.Repo
import com.yichincho.englishbbc.data.SpeedRule
import com.yichincho.englishbbc.player.PlayerHolder
import com.yichincho.englishbbc.ui.AppTheme
import com.yichincho.englishbbc.ui.LibraryScreen
import com.yichincho.englishbbc.ui.PlayerScreen
import com.yichincho.englishbbc.ui.SavedScreen
import com.yichincho.englishbbc.ui.SettingsScreen
import com.yichincho.englishbbc.ui.TodayScreen
import com.yichincho.englishbbc.ui.fmtSpeed
import com.yichincho.englishbbc.ui.rememberNow
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var player: PlayerHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        player = PlayerHolder(applicationContext)
        player.connect()
        setContent { AppTheme { AppRoot(player) } }
    }

    override fun onStop() {
        super.onStop()
        player.savePosition()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@Composable
private fun AppRoot(player: PlayerHolder) {
    val settings by Repo.settings.collectAsStateWithLifecycle()
    val library by Repo.library.collectAsStateWithLifecycle()
    val working by Repo.working.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSaved by rememberSaveable { mutableStateOf(false) }

    val now = rememberNow()
    val autoSpeed = SpeedRule.speedAt(now, settings)
    val speed = player.speedOverride ?: autoSpeed

    // Keeps the playing speed in step with the clock, and the position fresh for the transcript highlight.
    LaunchedEffect(player.controller, speed) { player.controller?.setPlaybackSpeed(speed) }
    LaunchedEffect(player.controller) {
        var ticks = 0
        while (true) {
            player.tick()
            if (++ticks % 25 == 0 && player.isPlaying) player.savePosition()
            delay(200)
        }
    }

    fun open(item: LibraryItem, startMs: Long? = null) {
        if (item.hasAudio) player.open(item, startMs)
        openId = item.episode.id
    }

    val openItem = library.firstOrNull { it.episode.id == openId }
    when {
        openItem != null -> {
            BackHandler { openId = null }
            PlayerScreen(
                item = openItem,
                stage = working[openItem.episode.id],
                settings = settings,
                player = player,
                speed = speed,
                autoSpeed = autoSpeed,
                onBack = { openId = null },
            )
        }

        showSaved -> {
            BackHandler { showSaved = false }
            SavedScreen(
                onBack = { showSaved = false },
                onOpen = { saved ->
                    library.firstOrNull { it.episode.id == saved.episodeId }?.let {
                        showSaved = false
                        open(it, (saved.startMs - 300).coerceAtLeast(0))
                    }
                },
            )
        }

        else -> Scaffold(
            bottomBar = {
                Column {
                    library.firstOrNull { it.episode.id == player.mediaId }?.let { playing ->
                        MiniPlayer(playing, player, speed, onOpen = { openId = playing.episode.id })
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("今天") },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null) }, label = { Text("節目庫") },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Rounded.Settings, null) }, label = { Text("設定") },
                        )
                    }
                }
            },
        ) { padding ->
            when (tab) {
                0 -> TodayScreen(
                    padding = padding,
                    settings = settings,
                    library = library,
                    working = working,
                    now = now,
                    onOpen = { open(it) },
                    onRemove = { player.stopIf(it.episode.id); Repo.remove(it.episode.id) },
                    onOpenSaved = { showSaved = true },
                    onGoLibrary = { tab = 1 },
                )

                1 -> LibraryScreen(padding, settings, library, working, onGoSettings = { tab = 2 })
                else -> SettingsScreen(padding, settings, now)
            }
        }
    }
}

@Composable
private fun MiniPlayer(item: LibraryItem, player: PlayerHolder, speed: Float, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.clickable(onClick = onOpen)) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${fmtSpeed(speed)} · ${if (player.isPlaying) "播放中" else "暫停"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { player.toggle() }) {
                    Icon(
                        if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (player.isPlaying) "暫停" else "播放",
                    )
                }
            }
        }
    }
}
