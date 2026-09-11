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
