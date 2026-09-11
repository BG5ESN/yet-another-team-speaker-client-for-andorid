package dev.tsdroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.tsdroid.han.R

class TsDroidApp : Application() {

    companion object {
        const val CHANNEL_ID_CONNECTION = "ts_connection"

        init {
            System.loadLibrary("tslib_jni")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃抓手：只做取证（Java 未捕获异常 + 上次进程死因），不改任何业务行为。
        // 放在最前面，保证连接/Rust 初始化之前就位。
        try {
            CrashCatcher.install(this)
        } catch (t: Throwable) {
            android.util.Log.w("TS3CRASH", "崩溃抓手安装失败", t)
        }
        // 把 Application context 交给 Rust 侧 ndk-context（幂等，装过一次就跳过）。
        // 新版底层 tsclientlib 用 hickory-resolver 解析服务器地址，Android 上
        // 必须拿到 Context 才能读系统 DNS，否则会在连接时 panic → 闪退。
        dev.tslib.Client.nativeSetAndroidContext(applicationContext)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_ID_CONNECTION,
            getString(R.string.channel_connection),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_connection_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
