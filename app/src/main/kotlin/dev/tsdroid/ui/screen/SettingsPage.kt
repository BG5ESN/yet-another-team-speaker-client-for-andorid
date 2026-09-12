package dev.tsdroid.ui.screen

import android.app.Activity
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
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
import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import dev.tsdroid.bridge.audio.dbfsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

                    // 麦克风 VA 测试：按住测自己的声音落在这把尺子的哪里（顺便验证门控会不会放过你）
                    MicTestSection(ringDb = ringDb)
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

/**
 * 麦克风 VA 测试。
 *
 * 按住按钮：用**独立采集通道**测麦克风电平，实时画在下面那条与门限同刻度的电平条上 ——
 * 电平超过门限的位置就变绿，代表"这一帧会被判为说话"（VA 模式会发出去）。
 * 松手立即停止采集，不常驻占用麦克风。
 *
 * 独立通道的好处：不依赖通话连接，**在设置页就能先把门限调好再连服务器**。
 * 代价：通话进行中测试会多开一路采集，极端机型上可能影响通话音质 —— 调门限建议先断开连接。
 */
@Composable
private fun MicTestSection(ringDb: Float) {
    val context = LocalContext.current
    var holding by remember { mutableStateOf(false) }
    var levelDb by remember { mutableStateOf(-120.0) }

    LaunchedEffect(holding) {
        if (!holding) {
            levelDb = -120.0
            return@LaunchedEffect
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(context, "需要麦克风权限才能测试", Toast.LENGTH_SHORT).show()
            holding = false
            return@LaunchedEffect
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,   // 与生产采集同一条输入路径
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SAMPLES * 2 * 4),
            )
        } catch (t: Throwable) {
            Log.w("MicTest", "创建 AudioRecord 失败", t)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            Toast.makeText(context, "麦克风不可用", Toast.LENGTH_SHORT).show()
            holding = false
            return@LaunchedEffect
        }

        try {
            record.startRecording()
            withContext(Dispatchers.IO) {
                val buf = ShortArray(FRAME_SAMPLES)
                var tick = 0
                while (isActive) {
                    val read = try {
                        record.read(buf, 0, FRAME_SAMPLES)
                    } catch (t: Throwable) {
                        break
                    }
                    // 25Hz 更新足够看清弹跳，不必 50Hz 刷 Compose
                    if (read > 0 && tick++ % 2 == 0) levelDb = dbfsOf(buf, read)
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            levelDb = -120.0
        }
    }

    Spacer(Modifier.height(10.dp))

    // 按住 = 采集，松手 = 停（不写成开关，避免忘了关一直占着麦克风）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (holding) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    holding = true
                    waitForUpOrCancellation()
                    holding = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.mic_test_hold),
            color = if (holding) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(8.dp))

    // 电平条：与上面滑块同一个刻度域（-60..-15 dBFS），竖线就是门限
    val speaking = levelDb > ringDb
    val levelFrac = ((levelDb + 60.0) / 45.0).coerceIn(0.0, 1.0).toFloat()
    val thresholdFrac = ((ringDb + 60f) / 45f).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val onColor = Color(0xFF4CAF50)
    val idleColor = MaterialTheme.colorScheme.outline
    val markColor = MaterialTheme.colorScheme.primary

    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
        ) {
            val h = size.height
            drawRoundRect(color = trackColor, cornerRadius = CornerRadius(h / 2))
            if (levelFrac > 0f) {
                drawRoundRect(
                    color = if (speaking) onColor else idleColor,
                    size = Size(size.width * levelFrac, h),
                    cornerRadius = CornerRadius(h / 2),
                )
            }
            // 门限刻度线
            val x = size.width * thresholdFrac
            drawRect(
                color = markColor,
                topLeft = Offset(x.coerceIn(0f, size.width - 2f), 0f),
                size = Size(2.dp.toPx(), h),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (speaking) stringResource(R.string.mic_test_speaking)
            else stringResource(R.string.mic_test_silent),
            style = MaterialTheme.typography.labelMedium,
            color = if (speaking) onColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = "${levelDb.toInt()} dBFS",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val SAMPLE_RATE = 48000
private const val FRAME_SAMPLES = 960
