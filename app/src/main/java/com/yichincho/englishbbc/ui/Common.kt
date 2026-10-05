package com.yichincho.englishbbc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yichincho.englishbbc.data.LibraryItem
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

fun fmtSpeed(speed: Float): String = String.format(Locale.US, "%.1fx", speed)

fun fmtDate(ms: Long): String {
    if (ms <= 0) return ""
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return "${d.monthValue}月${d.dayOfMonth}日"
}

fun fmtMinutes(durationSec: Int): String = if (durationSec > 0) "${(durationSec + 30) / 60} 分鐘" else ""

/** The current time, refreshed often enough to notice day turning into night. */
@Composable
fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = LocalDateTime.now()
        }
    }
    return now
}

enum class PillKind { OK, WARN, INFO }

@Composable
fun Pill(text: String, kind: PillKind, modifier: Modifier = Modifier, busy: Boolean = false) {
    val status = LocalStatusColors.current
    val (bg, fg) = when (kind) {
        PillKind.OK -> status.okBg to status.okFg
        PillKind.WARN -> status.warnBg to status.warnFg
        PillKind.INFO -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(12.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** One pill that says where an episode stands: being prepared, ready, how much is in Chinese, audio only, or failed. */
@Composable
fun EpisodeStatus(item: LibraryItem, stage: String?, modifier: Modifier = Modifier) {
    when {
        stage != null -> Pill(stage, PillKind.WARN, modifier, busy = true)
        item.hasTranscript && item.translationComplete -> Pill("原稿已備好 · 中文 100%", PillKind.OK, modifier)
        item.hasTranscript && item.sentenceCount > 0 ->
            Pill("原稿 100% · 中文 ${item.translatedCount * 100 / item.sentenceCount}%", PillKind.WARN, modifier)
        item.hasTranscript -> Pill("有原稿，沒翻完", PillKind.WARN, modifier)
        item.hasAudio -> Pill("只有聲音", PillKind.WARN, modifier)
        else -> Pill("還沒準備好", PillKind.WARN, modifier)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
