package dev.tsdroid.bridge.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 跟随系统自动路由（不指定偏好） */
const val FOLLOW_SYSTEM = -1

/** 一个可选的输出设备 */
data class AudioRouteDevice(
    val id: Int,
    /** 设备商品名。内置设备（扬声器/听筒）通常返回空串，UI 用类型名兜底 */
    val productName: String,
    val type: Int,
)

/**
 * 在"记住的选择"和"当前设备列表"之间找匹配项。
 *
 * 为什么要两步：蓝牙耳机重连后 [AudioDeviceInfo.getId] 往往会变（对内核来说是新实例），
 * 只按 id 找会认不出这副耳机。所以先按 id 精确匹配，失败再按 type + 商品名认。
 * 两个都认不出 → 返回 null，调用方应回退"跟随系统"（而不是死守一个不存在的偏好）。
 */
fun resolveSavedDevice(
    savedId: Int,
    savedType: Int,
    savedName: String,
    available: List<AudioRouteDevice>,
): AudioRouteDevice? {
    if (savedId == FOLLOW_SYSTEM) return null
    available.firstOrNull { it.id == savedId }?.let { return it }
    return available.firstOrNull { it.type == savedType && it.productName == savedName }
}

/**
 * 通话音频的输出设备路由。
 *
 * 用 [AudioTrack.setPreferredDevice] / [AudioRecord.setPreferredDevice]，**不是**
 * [AudioManager.setCommunicationDevice]。原因：
 *  · setCommunicationDevice 只在 MODE_IN_COMMUNICATION 下可调用（否则抛 IllegalStateException），
 *    而本项目从没设过音频模式 —— 动它等于顺带改掉 AEC / 音量键行为，是另一件事；
 *  · setPreferredDevice 无前置条件，只影响我们自己的这两条流，副作用最小；
 *  · 设备列表还能列出 A2DP 通道（蓝牙音乐通道），setCommunicationDevice 的候选里没有它。
 *
 * 注意 setPreferredDevice 是"偏好"而非强制：系统在更强的路由约束下（如通话中强占 SCO）
 * 可能不采纳。返回 false 只说明没被采纳，不代表设备不存在。
 */
class AudioRouteManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRoute"
    }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<AudioRouteDevice>>(emptyList())
    /** 当前可选的输出设备（插拔 / 蓝牙连断时实时刷新） */
    val devices: StateFlow<List<AudioRouteDevice>> = _devices.asStateFlow()

    private val _selectedId = MutableStateFlow(FOLLOW_SYSTEM)
    /** 用户选定的设备 id；[FOLLOW_SYSTEM] 表示交回系统自动路由 */
    val selectedId: StateFlow<Int> = _selectedId.asStateFlow()

    /** 记住的选择的类型/名字，用于设备 id 变化后重新认领（见 [resolveSavedDevice]） */
    private var savedType = 0
    private var savedName = ""

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        } catch (e: Exception) {
            Log.w(TAG, "registerAudioDeviceCallback 失败", e)
        }
        refresh()
    }

    fun stop() {
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
    }

    /**
     * 重新枚举设备。
     *
     * 只保留 [AudioDeviceInfo.isSink] 的，并排掉两类"能列出来但不能用"的：
     *  · [AudioDeviceInfo.TYPE_TELEPHONY] —— 电话用的虚拟设备，不是真实输出；
     *  · [AudioDeviceInfo.TYPE_FM]        —— FM 收音机（拿耳机线当天线），对通话输出无意义。
     * 用黑名单而不是白名单：将来系统新增设备类型能自动出现，不用改代码。
     */
    fun refresh() {
        val list = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter {
                    it.isSink &&
                        it.type != AudioDeviceInfo.TYPE_TELEPHONY &&
                        it.type != AudioDeviceInfo.TYPE_FM
                }
                .map { AudioRouteDevice(it.id, it.productName?.toString().orEmpty(), it.type) }
                .distinctBy { it.id }
        } catch (e: Exception) {
            Log.w(TAG, "枚举输出设备失败", e)
            emptyList()
        }
        _devices.value = list
        // 打出来便于对着 UI 核对：点开菜单该看到的就是这几项
        Log.i(TAG, "可选输出设备 ${list.size} 个: ${list.joinToString { nameOf(it) }}")

        // 选定的设备没了（拔耳机 / 蓝牙断开）→ 回退跟随系统，否则会永远握着一个不存在的偏好
        val id = _selectedId.value
        if (id != FOLLOW_SYSTEM && resolveSavedDevice(id, savedType, savedName, list) == null) {
            Log.i(TAG, "选定的输出设备已消失 (id=$id type=$savedType name=$savedName) → 回退跟随系统")
            _selectedId.value = FOLLOW_SYSTEM
        }
    }

    /**
     * 选择输出设备。传 [FOLLOW_SYSTEM] 交回系统自动路由。
     * 返回选中的设备（找不到 / 跟随系统时为 null）。
     */
    fun select(id: Int): AudioRouteDevice? {
        val dev = if (id == FOLLOW_SYSTEM) null else _devices.value.firstOrNull { it.id == id }
        if (id != FOLLOW_SYSTEM && dev == null) {
            Log.w(TAG, "要选的设备不在列表里 (id=$id)，忽略")
            return null
        }
        _selectedId.value = dev?.id ?: FOLLOW_SYSTEM
        savedType = dev?.type ?: 0
        savedName = dev?.productName ?: ""
        Log.i(TAG, "选定输出设备: ${dev?.let { nameOf(it) } ?: "跟随系统"}")
        return dev
    }

    /** 从设置里把上次的选择读回来（设备可能已不在，那就跟随系统） */
    fun restore(id: Int, type: Int, name: String) {
        savedType = type
        savedName = name
        val dev = resolveSavedDevice(id, type, name, _devices.value)
        _selectedId.value = dev?.id ?: FOLLOW_SYSTEM
        if (dev == null) {
            if (id != FOLLOW_SYSTEM) {
                Log.i(TAG, "上次选的输出设备当前不在 (id=$id type=$type name=$name) → 跟随系统")
            }
        } else {
            Log.i(TAG, "恢复输出设备: ${nameOf(dev)}")
        }
    }

    /**
     * 解析当前该用的设备。每次都重新解析 —— 设备 id 会在重连后变，
     * 缓存住 AudioDeviceInfo 会导致选中的耳机换了个 id 之后就失效。
     */
    private fun currentDevice(): AudioDeviceInfo? {
        val id = _selectedId.value
        if (id == FOLLOW_SYSTEM) return null
        return try {
            val sinks = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
            sinks.firstOrNull { it.id == id }
                ?: sinks.firstOrNull {
                    it.type == savedType && it.productName?.toString().orEmpty() == savedName
                }
        } catch (e: Exception) {
            Log.w(TAG, "解析当前设备失败", e)
            null
        }
    }

    /** 把偏好应用到播放轨道。返回系统是否采纳（false = 跟随系统 / 被拒） */
    fun applyTo(track: AudioTrack?): Boolean {
        val t = track ?: return false
        return try {
            val dev = currentDevice()
            val ok = t.setPreferredDevice(dev)
            Log.i(
                TAG,
                "播放路由: 目标=${dev?.let { nameOf(it) } ?: "跟随系统"} 采纳=$ok " +
                        "实际=${t.routedDevice?.let { nameOf(it) }}",
            )
            ok
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.setPreferredDevice 失败", e)
            false
        }
    }

    /**
     * 找到与指定输出设备对应的**输入**设备。
     *
     * 输出设备多半不是输入设备：扬声器、听筒根本没有采集端，把它们设给 AudioRecord
     * 只会被系统拒绝（日志表现为「采纳=false」）。蓝牙 / 有线 / USB 耳机才是双向的，
     * 但同一个物理设备在 GET_DEVICES_OUTPUTS 和 GET_DEVICES_INPUTS 里未必是同一个 id，
     * 所以要在输入列表里单独找：先按 id（双向设备 id 相同），再按 type + 商品名。
     *
     * 找不到就返回 null，交回系统默认（内置麦克风）—— 那是正确结果，不是失败。
     */
    private fun matchingInputFor(dev: AudioDeviceInfo): AudioDeviceInfo? {
        return try {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            // 匹配规则（先 id、再 type + 商品名）与 resolveSavedDevice 完全相同，直接复用 ——
            // 那边已经有 7 条单测覆盖，这里就不重复实现一份、也不重复测。
            val wantName = dev.productName?.toString().orEmpty()
            val match = resolveSavedDevice(
                dev.id, dev.type, wantName,
                inputs.map { AudioRouteDevice(it.id, it.productName?.toString().orEmpty(), it.type) },
            ) ?: return null
            inputs.firstOrNull { it.id == match.id }
        } catch (e: Exception) {
            Log.w(TAG, "枚举输入设备失败", e)
            null
        }
    }

    /**
     * 把偏好应用到录音。
     *
     * 麦克风跟着输出设备走：选了蓝牙耳机，采集也从耳机麦进 —— 这与 Android 的
     * CommunicationDevice 语义一致（输入输出成对），也是用户说"切到耳机"时的预期。
     * 但只在**该设备确实有采集端**时才设；扬声器/听筒这类单向下行设备交回系统默认。
     */
    fun applyTo(record: AudioRecord?): Boolean {
        val r = record ?: return false
        return try {
            val out = currentDevice()
            val dev = out?.let { matchingInputFor(it) }
            val ok = r.setPreferredDevice(dev)
            Log.i(
                TAG,
                "采集路由: 输出侧=${out?.let { nameOf(it) } ?: "跟随系统"} " +
                        "采集侧=${dev?.let { nameOf(it) } ?: "系统默认"} 采纳=$ok " +
                        "实际=${r.routedDevice?.let { nameOf(it) }}",
            )
            ok
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.setPreferredDevice 失败", e)
            false
        }
    }

    private fun nameOf(d: AudioDeviceInfo): String {
        val p = d.productName?.toString().orEmpty()
        return if (p.isBlank()) "type=${d.type}" else "$p(type=${d.type})"
    }

    /** 日志用：列表项版本（_devices 里存的是 AudioRouteDevice，不是 AudioDeviceInfo）*/
    private fun nameOf(d: AudioRouteDevice): String =
        if (d.productName.isBlank()) "type=${d.type}" else "${d.productName}(type=${d.type})"

}
