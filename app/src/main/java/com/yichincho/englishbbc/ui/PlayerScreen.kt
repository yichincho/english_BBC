package com.yichincho.englishbbc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichincho.englishbbc.data.LibraryItem
import com.yichincho.englishbbc.data.Repo
import com.yichincho.englishbbc.data.Sentence
import com.yichincho.englishbbc.data.Settings
import com.yichincho.englishbbc.player.PlayerHolder
import kotlinx.coroutines.delay

/** Gemini's timestamps are whole seconds, so start a little early to avoid clipping the first word. */
private const val LEAD_IN_MS = 300L

private val SPEED_CHOICES = listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)

@Composable
fun PlayerScreen(
    item: LibraryItem,
    stage: String?,
    settings: Settings,
    player: PlayerHolder,
    speed: Float,
    autoSpeed: Float,
    onBack: () -> Unit,
) {
    val id = item.episode.id
    val transcripts by Repo.transcripts.collectAsStateWithLifecycle()
    val saved by Repo.saved.collectAsStateWithLifecycle()
    val sentences = transcripts[id].orEmpty()

    LaunchedEffect(id, item.hasTranscript) { if (item.hasTranscript) Repo.loadTranscript(id) }

    val loaded = player.mediaId == id
    val position = if (loaded) player.positionMs else item.lastPositionMs
    val duration = if (loaded && player.durationMs > 0) player.durationMs else item.episode.durationSec * 1000L
    val current = sentences.indexOfLast { it.startMs <= position + LEAD_IN_MS }

    var hidden by rememberSaveable { mutableStateOf(false) }
    var repeatIndex by remember(id) { mutableStateOf<Int?>(null) }

    fun jumpTo(index: Int) {
        val s = sentences.getOrNull(index) ?: return
        if (repeatIndex != null) repeatIndex = index
        val start = (s.startMs - LEAD_IN_MS).coerceAtLeast(0)
        if (loaded) player.seekTo(start) else if (item.hasAudio) player.open(item, start)
    }

    // Repeat-one-sentence: jump back whenever playback runs past the end of the chosen sentence.
    LaunchedEffect(repeatIndex, sentences, loaded) {
        val index = repeatIndex ?: return@LaunchedEffect
        val s = sentences.getOrNull(index) ?: return@LaunchedEffect
        val start = (s.startMs - LEAD_IN_MS).coerceAtLeast(0)
        while (loaded) {
            val end = sentences.getOrNull(index + 1)?.startMs?.minus(LEAD_IN_MS) ?: player.durationMs
            val pos = player.controller?.currentPosition ?: break
            if (pos >= end || pos < start - 1500) player.seekTo(start)
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        TopBar(item.episode.title, speed, autoSpeed, player, onBack)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                sentences.isEmpty() -> NoTranscript(item, stage)
                hidden -> HiddenTranscript()
                else -> TranscriptList(sentences, current, settings, onTap = ::jumpTo)
            }
        }

        if (sentences.isNotEmpty()) {
            val currentSentence = sentences.getOrNull(current)
            val starred = currentSentence != null && saved.any { it.episodeId == id && it.startMs == currentSentence.startMs }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = settings.showZh, onClick = { Repo.updateSettings { it.copy(showZh = !it.showZh) } },
                    label = { Text("中文") }, leadingIcon = { Icon(Icons.Rounded.Translate, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = hidden, onClick = { hidden = !hidden },
                    label = { Text("藏原稿") },
                    leadingIcon = { Icon(if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = starred, enabled = currentSentence != null,
                    onClick = { currentSentence?.let { Repo.toggleSaved(item.episode, it) } },
                    label = { Text("收藏句") },
                    leadingIcon = { Icon(if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, Modifier.size(18.dp)) },
                )
            }
        }

        Controls(
            position = position,
            duration = duration,
            playing = loaded && player.isPlaying,
            enabled = item.hasAudio,
            repeating = repeatIndex != null,
            onSeek = { if (loaded) player.seekTo(it) else player.open(item, it) },
            onToggle = { if (loaded) player.toggle() else player.open(item) },
            onPrev = { jumpTo((current - 1).coerceAtLeast(0)) },
            onNext = { jumpTo(current + 1) },
            onRepeat = { repeatIndex = if (repeatIndex == null && current >= 0) current else null },
        )
    }
}

@Composable
private fun TopBar(title: String, speed: Float, autoSpeed: Float, player: PlayerHolder, onBack: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        Text(
            title, Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Box {
            AssistChip(
                onClick = { menu = true },
                label = { Text(if (player.speedOverride == null) "自動 ${fmtSpeed(speed)}" else fmtSpeed(speed)) },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("自動（現在 ${fmtSpeed(autoSpeed)}）") },
                    onClick = { player.speedOverride = null; menu = false },
                )
                SPEED_CHOICES.forEach { choice ->
                    DropdownMenuItem(text = { Text(fmtSpeed(choice)) }, onClick = { player.speedOverride = choice; menu = false })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptList(sentences: List<Sentence>, current: Int, settings: Settings, onTap: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0 && !listState.isScrollInProgress) listState.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    val size = settings.fontSizeSp
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        itemsIndexed(sentences) { index, s ->
            val isCurrent = index == current
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                    .clickable { onTap(index) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    s.en,
                    fontSize = size.sp, lineHeight = (size * 1.45f).sp,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                        index < current -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (settings.showZh && s.zh.isNotBlank()) {
                    Text(
                        s.zh,
                        fontSize = (size - 2).sp, lineHeight = ((size - 2) * 1.45f).sp,
                        color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isCurrent && settings.showZh && s.words.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.words.forEach { Pill("${it.en} ${it.zh}", PillKind.WARN) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenTranscript() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Text("原稿藏起來了，專心聽", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "聽不懂再按一次「藏原稿」看答案。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoTranscript(item: LibraryItem, stage: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            stage != null -> {
                Pill(stage, PillKind.WARN, busy = true)
                Text(
                    "原稿做好會自己出現，請讓 App 開著。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }

            item.hasTranscript -> Text("讀取原稿中", color = MaterialTheme.colorScheme.onSurfaceVariant)

            else -> {
                Text("這一集還沒有原稿", style = MaterialTheme.typography.titleMedium)
                if (item.error.isNotEmpty()) {
                    Text(item.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Button(onClick = { Repo.prepare(item.episode.id) }) { Text("做原稿") }
            }
        }
    }
}

@Composable
private fun Controls(
    position: Long,
    duration: Long,
    playing: Boolean,
    enabled: Boolean,
    repeating: Boolean,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Slider(
                value = fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek((it * duration).toLong()) }
                    dragging = null
                },
                enabled = enabled && duration > 0,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val label = MaterialTheme.typography.labelMedium
                Text(fmtTime((fraction * duration).toLong()), style = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtTime(duration), style = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onRepeat, enabled = enabled) {
                    Icon(
                        if (repeating) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = if (repeating) "停止重複這一句" else "重複這一句",
                        tint = if (repeating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onPrev, enabled = enabled) { Icon(Icons.Rounded.SkipPrevious, "上一句", Modifier.size(32.dp)) }
                FilledIconButton(onClick = onToggle, enabled = enabled, modifier = Modifier.size(64.dp), shape = CircleShape) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "暫停" else "播放",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onNext, enabled = enabled) { Icon(Icons.Rounded.SkipNext, "下一句", Modifier.size(32.dp)) }
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}
