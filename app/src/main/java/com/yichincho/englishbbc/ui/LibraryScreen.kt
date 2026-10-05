package com.yichincho.englishbbc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichincho.englishbbc.data.Episode
import com.yichincho.englishbbc.data.FeedState
import com.yichincho.englishbbc.data.LibraryItem
import com.yichincho.englishbbc.data.Repo
import com.yichincho.englishbbc.data.Settings
import com.yichincho.englishbbc.data.stageHeadline

@Composable
fun LibraryScreen(
    padding: PaddingValues,
    settings: Settings,
    library: List<LibraryItem>,
    working: Map<String, String>,
    onGoSettings: () -> Unit,
) {
    val feedStates by Repo.feeds.collectAsStateWithLifecycle()
    val feeds = settings.visibleFeeds()
    var selectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val feed = feeds.firstOrNull { it.url == selectedUrl } ?: feeds.firstOrNull()
    val state = feed?.let { feedStates[it.url] } ?: FeedState()

    LaunchedEffect(feed?.url) { feed?.let { Repo.loadFeed(it.url) } }

    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("節目庫", modifier = Modifier.weight(1f))
            if (feed != null) {
                IconButton(onClick = { Repo.loadFeed(feed.url, force = true) }) { Icon(Icons.Rounded.Refresh, "重新整理") }
            }
        }

        if (feed == null) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("還沒有選節目來源", style = MaterialTheme.typography.titleMedium)
                Text("到設定勾選想聽的 BBC 節目。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onGoSettings) { Text("去設定") }
            }
            return@Column
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(feeds, key = { it.url }) { f ->
                FilterChip(selected = f.url == feed.url, onClick = { selectedUrl = f.url }, label = { Text(f.title) })
            }
        }

        if (state.error.isNotEmpty()) {
            Text(
                state.error, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.loading && state.episodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                items(state.episodes, key = { it.id }) { episode ->
                    val item = library.firstOrNull { it.episode.id == episode.id }
                    FeedEpisodeRow(episode, item, working[episode.id])
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    Text(
                        "按「加入」會下載聲音並做原稿，做的時候請讓 App 開著。",
                        Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedEpisodeRow(episode: Episode, item: LibraryItem?, stage: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(episode.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOf(fmtDate(episode.pubDateMs), fmtMinutes(episode.durationSec)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (item == null) {
            FilledTonalButton(onClick = { Repo.add(episode) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("加入")
            }
        } else if (stage != null) {
            Pill(stageHeadline(stage), PillKind.WARN, busy = true)
        } else {
            Pill("已加入", PillKind.OK)
        }
    }
}
