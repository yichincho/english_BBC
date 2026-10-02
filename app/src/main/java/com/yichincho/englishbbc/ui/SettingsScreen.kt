package com.yichincho.englishbbc.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yichincho.englishbbc.data.Ai
import com.yichincho.englishbbc.data.DEFAULT_FEEDS
import com.yichincho.englishbbc.data.PRESET_MODELS
import com.yichincho.englishbbc.data.Provider
import com.yichincho.englishbbc.data.Repo
import com.yichincho.englishbbc.data.Settings
import com.yichincho.englishbbc.data.SpeedRule
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(padding: PaddingValues, settings: Settings, now: LocalDateTime) {
    Column(
        Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("設定")

        SectionLabel("AI 金鑰", Modifier.padding(top = 8.dp))
        SettingsCard {
            Provider.entries.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProviderRow(p, settings)
            }
        }
        Text(
            "金鑰只存在這支手機裡。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("翻譯用哪一家", Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.translator == "auto",
                onClick = { Repo.updateSettings { it.copy(translator = "auto") } }, label = { Text("自動") },
            )
            Provider.entries.forEach { p ->
                FilterChip(
                    selected = settings.translator == p.name,
                    onClick = { Repo.updateSettings { it.copy(translator = p.name) } }, label = { Text(p.label) },
                )
            }
        }
        Text(
            when (val p = settings.translatorProvider()) {
                null -> "還沒有任何金鑰，所以不會有中文。"
                else -> "現在會用 ${p.label} 翻中文。"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("速度", Modifier.padding(top = 8.dp))
        SettingsCard {
            val cycle = SpeedRule.cycleIndex(now, settings.nightStartMinute, settings.dayStartMinute, settings.cycleStartEpochDay)
            TimeRow(Icons.Rounded.WbSunny, "白天開始", "固定 1.0x", settings.dayStartMinute, 3 * 60..11 * 60) { m ->
                Repo.updateSettings { it.copy(dayStartMinute = m) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TimeRow(Icons.Rounded.NightsStay, "晚上開始", "每天 +0.2，到 2.0 重來", settings.nightStartMinute, 12 * 60..23 * 60 + 30) { m ->
                Repo.updateSettings { it.copy(nightStartMinute = m) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("今天是第 ${cycle + 1} 天", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "晚上 ${fmtSpeed(SpeedRule.nightSpeed(cycle))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { Repo.updateSettings { it.copy(cycleStartEpochDay = it.cycleStartEpochDay + cycle) } },
                    enabled = cycle != 0,
                ) { Text("從第 1 天重來") }
            }
        }

        SectionLabel("原稿字體大小：${settings.fontSizeSp}", Modifier.padding(top = 8.dp))
        Slider(
            value = settings.fontSizeSp.toFloat(),
            onValueChange = { v -> Repo.updateSettings { it.copy(fontSizeSp = v.roundToInt()) } },
            valueRange = 12f..26f,
            steps = 13,
        )

        SectionLabel("節目來源", Modifier.padding(top = 8.dp))
        SettingsCard { FeedList(settings) }
        AddFeed()
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column { content() }
    }
}

private fun purposeOf(p: Provider) = when (p) {
    Provider.GEMINI -> "聽聲音、寫原稿（一定要有）"
    Provider.NVIDIA -> "翻中文、解釋單字（免費金鑰，可選型號）"
    Provider.DEEPSEEK -> "翻中文、解釋單字（DeepSeek 官方）"
}

@Composable
private fun ProviderRow(p: Provider, settings: Settings) {
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(p) { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    val presets = PRESET_MODELS[p].orEmpty()
    val choices = presets + (models - presets.toSet())
    // Typed text lives here and is written through, so the cursor never waits on the settings round trip.
    var key by remember { mutableStateOf(settings.key(p)) }
    var model by remember { mutableStateOf(settings.model(p)) }

    Column {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.label, style = MaterialTheme.typography.bodyLarge)
                Text(purposeOf(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (key.isBlank()) Pill("未設定", PillKind.WARN) else Pill("已設定", PillKind.OK)
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { v ->
                        key = v.trim()
                        message = ""
                        Repo.updateSettings { it.withKey(p, key) }
                    },
                    label = { Text("API 金鑰") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                if (showKey) "藏起金鑰" else "顯示金鑰",
                            )
                        }
                    },
                )
                Box {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { v ->
                            model = v.trim()
                            Repo.updateSettings { it.withModel(p, model) }
                        },
                        label = { Text("型號") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (choices.isNotEmpty()) {
                                IconButton(onClick = { modelMenu = true }) { Icon(Icons.Rounded.ExpandMore, "從清單選型號") }
                            }
                        },
                    )
                    DropdownMenu(
                        expanded = modelMenu, onDismissRequest = { modelMenu = false },
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        choices.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    model = m
                                    Repo.updateSettings { it.withModel(p, m) }
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        enabled = key.isNotBlank() && !testing,
                        onClick = {
                            testing = true
                            message = ""
                            scope.launch {
                                val result = Repo.testProvider(p, key, model)
                                testing = false
                                failed = result.isFailure
                                models = result.getOrDefault(emptyList())
                                message = result.fold(
                                    onSuccess = { list ->
                                        val suggested = Ai.suggestModel(p, list, model)
                                        when {
                                            suggested != model -> {
                                                model = suggested
                                                Repo.updateSettings { it.withModel(p, suggested) }
                                                "金鑰可以用。原本的型號沒了，已換成 $suggested。"
                                            }
                                            model in list -> "金鑰可以用，型號也對。"
                                            else -> "金鑰可以用，但清單裡沒有這個型號，按右邊箭頭選一個。"
                                        }
                                    },
                                    onFailure = { it.message ?: "連不上" },
                                )
                            }
                        },
                    ) { Text(if (testing) "測試中" else "測試金鑰") }
                }
                if (message.isNotEmpty()) {
                    Text(
                        message, style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeRow(icon: ImageVector, title: String, subtitle: String, minute: Int, range: IntRange, onChange: (Int) -> Unit) {
    val step = 30
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onChange(minute - step) }, enabled = minute > range.first) { Icon(Icons.Rounded.Remove, "早半小時") }
        Text("%02d:%02d".format(minute / 60, minute % 60), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(minute + step) }, enabled = minute < range.last) { Icon(Icons.Rounded.Add, "晚半小時") }
    }
}

@Composable
private fun FeedList(settings: Settings) {
    settings.allFeeds().forEachIndexed { i, feed ->
        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val enabled = feed.url in settings.enabledFeeds
        fun toggle() = Repo.updateSettings {
            it.copy(enabledFeeds = if (enabled) it.enabledFeeds - feed.url else it.enabledFeeds + feed.url)
        }
        Row(
            Modifier.fillMaxWidth().clickable { toggle() }.padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = enabled, onCheckedChange = { toggle() })
            Text(feed.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (feed !in DEFAULT_FEEDS) {
                IconButton(onClick = {
                    Repo.updateSettings { it.copy(customFeeds = it.customFeeds - feed, enabledFeeds = it.enabledFeeds - feed.url) }
                }) { Icon(Icons.Rounded.DeleteOutline, "移除這個來源", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun AddFeed() {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = "" },
            label = { Text("加入別的 RSS 網址") },
            placeholder = { Text("https://podcasts.files.bbci.co.uk/p02nq0gn.rss") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        FilledTonalButton(
            enabled = !busy,
            onClick = {
                val trimmed = url.trim()
                if (!trimmed.startsWith("http")) {
                    error = "先貼上 http 開頭的網址"
                    return@FilledTonalButton
                }
                busy = true
                scope.launch {
                    error = Repo.addCustomFeed(trimmed).orEmpty()
                    if (error.isEmpty()) url = ""
                    busy = false
                }
            },
        ) { Text(if (busy) "檢查中" else "加入") }
    }
    if (error.isNotEmpty()) {
        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
