package com.yichincho.englishbbc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichincho.englishbbc.data.Topic

/** Index of the topic that sentence [current] belongs to, or -1 before playback starts. */
internal fun topicAt(topics: List<Topic>, current: Int): Int =
    if (current < 0) -1 else topics.indexOfLast { it.start <= current }

/**
 * The episode's topics with their English key points and Chinese, shown above the transcript.
 * Tapping a topic title plays from where it starts; the topic now playing is marked.
 */
@Composable
internal fun TopicCard(
    episodeId: String,
    topics: List<Topic>?,
    making: Boolean,
    error: String?,
    current: Int,
    fontSizeSp: Int,
    onJump: (Int) -> Unit,
    onRemake: () -> Unit,
) {
    var expanded by rememberSaveable(episodeId) { mutableStateOf(true) }
    val playing = topicAt(topics.orEmpty(), current)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (topics.isNullOrEmpty()) "主題重點" else "主題重點（${topics.size} 個主題）",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "收起" else "展開")
            }
            if (!expanded) return@Column
            when {
                !topics.isNullOrEmpty() -> {
                    topics.forEachIndexed { i, topic ->
                        if (i > 0) HorizontalDivider()
                        TopicView(i, topic, i == playing, fontSizeSp) { onJump(topic.start) }
                    }
                    TextButton(enabled = !making, onClick = onRemake) { Text(if (making) "重新整理中…" else "重新整理重點") }
                }
                making -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("AI 正在整理每個主題的重點…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error != null -> {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRemake) { Text("再試一次") }
                }
                else -> Text(
                    "翻譯完成後會自動整理重點（需要設定 AI 金鑰）。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TopicView(index: Int, topic: Topic, playing: Boolean, fontSizeSp: Int, onJump: () -> Unit) {
    val accent = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onJump)) {
            Text(
                "${index + 1}. ${topic.title}" + if (playing) "　▶ 播放中" else "",
                fontWeight = FontWeight.Bold, color = accent, fontSize = fontSizeSp.sp,
            )
            if (topic.titleZh.isNotBlank()) {
                Text(topic.titleZh, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = (fontSizeSp - 2).sp)
            }
        }
        topic.points.forEach { point ->
            Row {
                Text("• ", fontSize = fontSizeSp.sp)
                Column {
                    Text(point.en, fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.4f).sp)
                    Text(
                        point.zh, fontSize = (fontSizeSp - 2).sp, lineHeight = ((fontSizeSp - 2) * 1.4f).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A thin label in the transcript where a new topic begins. */
@Composable
internal fun TopicDivider(index: Int, topic: Topic) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  主題 ${index + 1} · ${topic.titleZh.ifBlank { topic.title }}  ",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}
