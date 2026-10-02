package com.yichincho.englishbbc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichincho.englishbbc.data.LibraryItem
import com.yichincho.englishbbc.data.Repo
import com.yichincho.englishbbc.data.Settings
import com.yichincho.englishbbc.data.SpeedRule
import java.time.LocalDateTime

@Composable
fun TodayScreen(
    padding: PaddingValues,
    settings: Settings,
    library: List<LibraryItem>,
    working: Map<String, String>,
    now: LocalDateTime,
    onOpen: (LibraryItem) -> Unit,
    onRemove: (LibraryItem) -> Unit,
    onOpenSaved: () -> Unit,
    onGoLibrary: () -> Unit,
) {
    val saved by Repo.saved.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<LibraryItem?>(null) }

    val night = SpeedRule.isNight(now.hour, settings.nightStartHour, settings.dayStartHour)
    val cycle = SpeedRule.cycleIndex(now, settings.nightStartHour, settings.dayStartHour, settings.cycleStartEpochDay)
    val speed = SpeedRule.speedAt(now, settings)
    val featured = library.firstOrNull()

    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenTitle("今天", "${now.monthValue}月${now.dayOfMonth}日 · ${if (night) "晚上" else "白天"} · 第 ${cycle + 1} 天")
        }
        item { SpeedCard(night, cycle) }

        if (featured == null) {
            item { EmptyLibrary(onGoLibrary) }
        } else {
            item { FeaturedCard(featured, working[featured.episode.id], speed, onOpen = { onOpen(featured) }, onRemove = { removing = featured }) }
        }

        val rest = library.drop(1)
        if (rest.isNotEmpty()) {
            item { SectionLabel("等等要聽", Modifier.padding(top = 8.dp)) }
            items(rest, key = { it.episode.id }) { item ->
                EpisodeRow(item, working[item.episode.id], onOpen = { onOpen(item) }, onRemove = { removing = item })
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenSaved).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.StarBorder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("收藏的句子", style = MaterialTheme.typography.bodyLarge)
                    Text("${saved.size} 句", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    removing?.let { item ->
        ConfirmDialog(
            title = "移除這一集？",
            text = "「${item.episode.title}」的聲音和原稿會從手機刪掉。",
            confirm = "移除",
            onConfirm = { onRemove(item) },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun SpeedCard(night: Boolean, cycle: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                SpeedHalf(Icons.Rounded.WbSunny, "白天", 1f, active = !night, Modifier.weight(1f))
                SpeedHalf(Icons.Rounded.NightsStay, "晚上", SpeedRule.nightSpeed(cycle), active = night, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(SpeedRule.CYCLE_DAYS) { i ->
                    Box(
                        Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(
                            if (i <= cycle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
            Text(
                "晚上每天快一點：1.2 → 1.4 → 1.6 → 1.8 → 2.0，然後重來",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeedHalf(icon: ImageVector, label: String, speed: Float, active: Boolean, modifier: Modifier) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Text(if (active) "$label · 現在" else label, style = MaterialTheme.typography.labelLarge, color = color)
        }
        Text(
            fmtSpeed(speed),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FeaturedCard(item: LibraryItem, stage: String?, speed: Float, onOpen: () -> Unit, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.episode.feedTitle, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.DeleteOutline, "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(item.episode.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(fmtMinutes(item.episode.durationSec), "聽過 ${item.listenCount} 次").filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EpisodeStatus(item, stage)
            if (item.error.isNotEmpty() && stage == null) {
                Text(item.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f), enabled = item.hasAudio) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (item.hasAudio) "開始聽 ${fmtSpeed(speed)}" else "聲音還在路上")
                }
                if (stage == null && (item.error.isNotEmpty() || !item.hasTranscript)) {
                    FilledTonalButton(onClick = { Repo.prepare(item.episode.id) }) { Text("再試一次") }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(item: LibraryItem, stage: String?, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.episode.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EpisodeStatus(item, stage)
                Text("聽過 ${item.listenCount} 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.DeleteOutline, "移除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyLibrary(onGoLibrary: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("挑一集開始聽", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "到節目庫按「加入」，App 會下載聲音並做出原稿。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGoLibrary) { Text("去節目庫") }
        }
    }
}
