package dev.tsdroid.ui.screen

import android.app.Activity
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.tsdroid.data.SettingsStore
import dev.tsdroid.han.R
import kotlinx.coroutines.launch

@Composable
fun SettingsPage(
    onNavigateToAbout: () -> Unit,
    autoReconnect: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val showLinkThumbnails by settingsStore.showLinkThumbnails.collectAsStateWithLifecycle(initialValue = false)
    val autoLoadImages by settingsStore.autoLoadImages.collectAsStateWithLifecycle(initialValue = true)
    val enableFloatingWindow by settingsStore.enableFloatingWindow.collectAsStateWithLifecycle(initialValue = false)
    val noiseSuppression by settingsStore.noiseSuppression.collectAsStateWithLifecycle(initialValue = true)
    val audioGain by settingsStore.audioGain.collectAsStateWithLifecycle(initialValue = 1.0f)
    val persistedRingDb by settingsStore.ringThresholdDb.collectAsStateWithLifecycle(initialValue = -40f)
    // 拖动期间用本地值显示（避免与持久化来回打架），松手才落盘
    var dragRingDb by remember { mutableStateOf<Float?>(null) }
    val ringDb = dragRingDb ?: persistedRingDb

    val languageOptions = listOf(
        "zh" to stringResource(R.string.language_simplified_chinese),
        "en" to stringResource(R.string.language_english),
        "fr" to stringResource(R.string.language_french),
    )
    val selectedLanguageTag by settingsStore.language.collectAsStateWithLifecycle(initialValue = "zh")
    val selectedLanguageLabel = languageOptions.firstOrNull { it.first == selectedLanguageTag }?.second
        ?: stringResource(R.string.language_simplified_chinese)
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var pendingLanguageTag by remember { mutableStateOf<String?>(null) }
    val activity = context as? Activity

    pendingLanguageTag?.let { languageTag ->
        val label = languageOptions.firstOrNull { it.first == languageTag }?.second ?: languageTag
        AlertDialog(
            onDismissRequest = { pendingLanguageTag = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.language_change_title)) },
            text = { Text(stringResource(R.string.language_change_message, label)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        settingsStore.setLanguage(languageTag)
                        activity?.recreate()
                    }
                    pendingLanguageTag = null
                }) {
                    Text(stringResource(R.string.restart))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanguageTag = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // ── 外观 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.section_appearance))

                // 悬浮窗
                SettingsSwitchRow(
                    label = stringResource(R.string.enable_floating_window),
                    checked = enableFloatingWindow,
                    onCheckedChange = { scope.launch { settingsStore.setEnableFloatingWindow(it) } },
                )

            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 音频 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.section_audio))

                // 音量增益
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = "${stringResource(R.string.audio_gain)} : ${stringResource(R.string.audio_gain_value, audioGain)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = audioGain,
                        onValueChange = { scope.launch { settingsStore.setAudioGain(it) } },
                        valueRange = 1.0f..8.0f,
                        steps = 13,
                    )
                }

                // 说话圈门限（dBFS）：频道树头像圈 + 悬浮窗气泡都按它判"谁在说话"
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = "${stringResource(R.string.ring_threshold)} : ${ringDb.toInt()} dBFS",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = ringDb,
                        onValueChange = { dragRingDb = it },
                        onValueChangeFinished = {
                            dragRingDb?.let { v -> scope.launch { settingsStore.setRingThresholdDb(v) } }
                            dragRingDb = null
                        },
                        valueRange = -60f..-15f,
                        steps = 44,
                    )
                }

                // 麦克风降噪
                SettingsSwitchRow(
                    label = stringResource(R.string.noise_suppression),
                    checked = noiseSuppression,
                    onCheckedChange = { scope.launch { settingsStore.setNoiseSuppression(it) } },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 聊天 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.section_chat))

                SettingsSwitchRow(
                    label = stringResource(R.string.auto_reconnect),
                    checked = autoReconnect,
                    onCheckedChange = onAutoReconnectChange,
                )
                SettingsSwitchRow(
                    label = stringResource(R.string.show_link_thumbnails),
                    checked = showLinkThumbnails,
                    onCheckedChange = { scope.launch { settingsStore.setShowLinkThumbnails(it) } },
                )
                SettingsSwitchRow(
                    label = stringResource(R.string.auto_load_images),
                    checked = autoLoadImages,
                    onCheckedChange = { scope.launch { settingsStore.setAutoLoadImages(it) } },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 更多 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSectionTitle(stringResource(R.string.section_more))

                // 语言切换
                SettingsClickableRow(
                    label = stringResource(R.string.language_change_title),
                    trailing = {
                        Box {
                            Text(
                                text = selectedLanguageLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { languageMenuExpanded = true },
                            )
                            DropdownMenu(
                                expanded = languageMenuExpanded,
                                onDismissRequest = { languageMenuExpanded = false },
                            ) {
                                languageOptions.forEach { (tag, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            pendingLanguageTag = tag
                                            languageMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                )

                // 关于软件
                SettingsClickableRow(
                    label = stringResource(R.string.about_software),
                    onClick = onNavigateToAbout,
                )

            }
        }

        Spacer(Modifier.height(32.dp))

        // 版本号
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        Text(
            text = "TS3 v$versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

// ── 可复用组件 ──

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsClickableRow(
    label: String,
    onClick: () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {
        Icon(Icons.AutoMirrored.Filled.NavigateNext, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}
