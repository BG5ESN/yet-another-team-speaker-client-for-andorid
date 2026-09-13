package dev.tsdroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private val KEY_AUDIO_GAIN = floatPreferencesKey("audio_gain")
private val KEY_SHOW_LINK_THUMBNAILS = booleanPreferencesKey("show_link_thumbnails")
private val KEY_AUTO_LOAD_IMAGES = booleanPreferencesKey("auto_load_images")
private val KEY_LANGUAGE = stringPreferencesKey("language")
private val KEY_ENABLE_FLOATING_WINDOW = booleanPreferencesKey("enable_floating_window")
private val KEY_NOISE_SUPPRESSION = booleanPreferencesKey("noise_suppression")
private val KEY_RING_THRESHOLD_DB = floatPreferencesKey("ring_threshold_db")
// 输出设备路由：跟随系统存 -1；否则存 id + type + 名字（id 会在蓝牙重连后变，名字是兜底）
private val KEY_EXCLUSIVE_AUDIO = booleanPreferencesKey("exclusive_audio")
private val KEY_ROUTE_DEVICE_ID = intPreferencesKey("route_device_id")
private val KEY_ROUTE_DEVICE_TYPE = intPreferencesKey("route_device_type")
private val KEY_ROUTE_DEVICE_NAME = stringPreferencesKey("route_device_name")

class SettingsStore(private val context: Context) {

    val audioGain: Flow<Float> = context.settingsDataStore.data
        .map { it[KEY_AUDIO_GAIN] ?: 1.0f }

    val showLinkThumbnails: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_SHOW_LINK_THUMBNAILS] ?: false }

    val autoLoadImages: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_AUTO_LOAD_IMAGES] ?: true }

    val language: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_LANGUAGE] ?: "zh" }

    val enableFloatingWindow: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_ENABLE_FLOATING_WINDOW] ?: true }

    suspend fun setAudioGain(gain: Float) {
        context.settingsDataStore.edit { it[KEY_AUDIO_GAIN] = gain }
    }

    suspend fun setShowLinkThumbnails(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_LINK_THUMBNAILS] = enabled }
    }

    suspend fun setAutoLoadImages(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_LOAD_IMAGES] = enabled }
    }

    suspend fun setLanguage(language: String) {
        context.settingsDataStore.edit { it[KEY_LANGUAGE] = language }
    }

    suspend fun setEnableFloatingWindow(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_ENABLE_FLOATING_WINDOW] = enabled }
    }

    val noiseSuppression: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_NOISE_SUPPRESSION] ?: true }

    suspend fun setNoiseSuppression(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_NOISE_SUPPRESSION] = enabled }
    }

    /** 说话圈门限（dBFS，默认 -40）：说话圈/悬浮窗按这个门限判"谁在说话" */
    val ringThresholdDb: Flow<Float> = context.settingsDataStore.data
        .map { it[KEY_RING_THRESHOLD_DB] ?: -40f }

    suspend fun setRingThresholdDb(db: Float) {
        context.settingsDataStore.edit { it[KEY_RING_THRESHOLD_DB] = db }
    }

    /**
     * 输出设备选择：id = -1 表示跟随系统。
     * 存 type + name 是因为蓝牙耳机重连后 id 会变，只认 id 就认不出那副耳机了。
     */
    val routeDeviceId: Flow<Int> = context.settingsDataStore.data
        .map { it[KEY_ROUTE_DEVICE_ID] ?: -1 }

    val routeDeviceType: Flow<Int> = context.settingsDataStore.data
        .map { it[KEY_ROUTE_DEVICE_TYPE] ?: 0 }

    val routeDeviceName: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_ROUTE_DEVICE_NAME] ?: "" }

    /**
     * 通话独占声音：true = 抢音频焦点（音乐类 App 收到 LOSS 会暂停）；
     * false = 完全不抢，我们的语音和音乐同时出声。
     *
     * 默认 true（即原有行为）。用户手动切 —— 因为 Android 不提供查询别的 App
     * 音频路由的 API，"源是否相同"测不出来，只能由用户按场景决定。
     */
    val exclusiveAudio: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_EXCLUSIVE_AUDIO] ?: true }

    suspend fun setExclusiveAudio(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_EXCLUSIVE_AUDIO] = enabled }
    }

    suspend fun setRouteDevice(id: Int, type: Int, name: String) {
        context.settingsDataStore.edit {
            it[KEY_ROUTE_DEVICE_ID] = id
            it[KEY_ROUTE_DEVICE_TYPE] = type
            it[KEY_ROUTE_DEVICE_NAME] = name
        }
    }
}
