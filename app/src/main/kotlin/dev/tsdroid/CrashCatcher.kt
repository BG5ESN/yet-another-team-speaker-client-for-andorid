package dev.tsdroid

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃抓手：**只做取证，不改任何业务行为**。
 *
 * 抓两类：
 *  1) Java 未捕获异常 —— 当场写盘 + logcat；
 *  2) 上次进程死因 —— native 崩溃 / ANR / 被系统杀（ApplicationExitInfo，API 30+），
 *     下次启动补记。**原生 SIGSEGV / ANR 用 Java handler 抓不到**，只能靠这条。
 *
 * 报告到处都有，从"方便人取"到"方便 adb 取"：
 *  · 系统「下载」根目录：ts3-crash-latest.txt（永远是最新一份）+ ts3-crash-<类型>-<时间>.txt
 *    —— 朋友用文件管理就能看到，**不用 adb、不用 root**
 *  · 崩溃后下次启动弹一条通知，**点一下直接分享**（微信/QQ 发回给你）
 *  · logcat（tag=TS3CRASH，分段输出）
 *  · 应用私有目录 files/crash-*.txt（adb run-as 可取）
 */
object CrashCatcher {
    private const val TAG = "TS3CRASH"
    private const val MAX_CHARS = 200_000
    private const val LOG_CHUNK = 3000
    private const val PREFS = "crash_catcher"
    private const val KEY_LAST_EXIT_TS = "last_reported_exit_ts"
    private const val EXIT_LOOKBACK_MS = 48 * 3600_000L
    private const val LATEST_NAME = "ts3-crash-latest.txt"
    private const val NOTIFY_ID = 0x7A31

    fun install(context: Context) {
        val app = context.applicationContext

        // ── 1) Java 未捕获异常（当场记录） ──
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(app, "JAVA", buildString {
                    append("thread=").append(thread.name).append('\n')
                    append(Log.getStackTraceString(throwable))
                })
            } catch (_: Throwable) {
                // 抓手自己绝不能把崩溃流程搞坏
            }
            if (prev != null) {
                prev.uncaughtException(thread, throwable)   // 交回默认处理：系统行为不变
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }

        // ── 2) 上次进程死因（native 崩溃 / ANR / 被系统杀） ──
        try {
            reportPreviousExits(app)
        } catch (t: Throwable) {
            Log.w(TAG, "reportPreviousExits 失败", t)
        }

        Log.i(TAG, "崩溃抓手已就位（Java handler + ApplicationExitInfo）")
    }

    /** 从系统历史里捞"上次为什么死"，写完记水位，避免每次启动重复写同一份 */
    private fun reportPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val infos: List<ApplicationExitInfo> =
            am.getHistoricalProcessExitReasons(context.packageName, 0, 20) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sinceTs = prefs.getLong(KEY_LAST_EXIT_TS, System.currentTimeMillis() - EXIT_LOOKBACK_MS)
        var newest = sinceTs
        var reported = 0

        for (info in infos) {
            if (info.timestamp <= sinceTs) break          // 列表按时间倒序
            if (info.timestamp > newest) newest = info.timestamp
            val interesting = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> true
                else -> false
            }
            if (!interesting) continue

            val trace = try {
                info.traceInputStream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "(无 trace)"
            } catch (t: Throwable) {
                "(读 trace 失败: $t)"
            }
            val reason = reasonName(info.reason)
            val uri = write(context, "EXIT-$reason", buildString {
                append("退出时间=").append(fmt(info.timestamp)).append('\n')
                append("原因=").append(reason).append('\n')
                append("进程=").append(info.processName).append(" status=").append(info.status).append('\n')
                append("系统描述=").append(info.description).append('\n')
                append("importance=").append(info.importance)
                append(" pss=").append(info.pss).append("KB rss=").append(info.rss).append("KB\n")
                append("---- trace ----\n").append(trace)
            })
            if (reported == 0) notifyUser(context, reason, info.timestamp, uri)
            reported++
            if (reported >= 6) break
        }

        if (newest > sinceTs) prefs.edit().putLong(KEY_LAST_EXIT_TS, newest).apply()
        if (reported == 0) Log.i(TAG, "过去 48 小时没有异常退出记录")
    }

    /** 写报告：下载根目录（最新一份 + 带时间的）+ 私有目录 + logcat。返回可分享的 Uri */
    private fun write(context: Context, kind: String, body: String): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val text = buildString {
            append("===== TS3 崩溃报告 =====\n")
            append("kind=").append(kind).append("  记录时间=").append(stamp).append('\n')
            append("机型=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append("  API=").append(Build.VERSION.SDK_INT).append('\n')
            append("应用=").append(appVersion(context)).append("  pid=").append(Process.myPid()).append('\n')
            append("===== 正文 =====\n")
            append(body.take(MAX_CHARS))
        }

        // ① logcat：分段，避免单条超长被截断
        var idx = 0
        var part = 0
        while (idx < text.length) {
            val end = minOf(idx + LOG_CHUNK, text.length)
            Log.e(TAG, "[$part] " + text.substring(idx, end))
            idx = end
            part++
        }

        // ② 应用私有目录（adb 取）
        try {
            File(context.filesDir, "crash-$kind-$stamp.txt").writeText(text)
            context.filesDir.listFiles { _, n -> n.startsWith("crash-") }
                ?.sortedBy { it.lastModified() }
                ?.dropLast(20)
                ?.forEach { it.delete() }
        } catch (t: Throwable) {
            Log.w(TAG, "写 filesDir 失败", t)
        }

        // ③ 系统「下载」根目录（朋友用文件管理就能看到 / 分享）
        var shareUri: Uri? = null
        try {
            shareUri = putDownloads(context, "ts3-crash-$kind-$stamp.txt", text)
            putDownloads(context, LATEST_NAME, text, replace = true)
        } catch (t: Throwable) {
            Log.w(TAG, "写下载目录失败", t)
        }
        return shareUri
    }

    /** 往系统「下载」写一个 txt；replace=true 时先删掉同名的旧文件（免得攒成 xxx (1).txt） */
    private fun putDownloads(
        context: Context,
        name: String,
        text: String,
        replace: Boolean = false,
    ): Uri? {
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val rel = Environment.DIRECTORY_DOWNLOADS
        if (replace) {
            try {
                context.contentResolver.delete(
                    coll,
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(name, "$rel/"),
                )
            } catch (_: Throwable) {
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, rel)
        }
        val uri = context.contentResolver.insert(coll, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        return uri
    }

    /** 崩溃后已经"重启回来"了：弹一条通知，点一下直接分享日志（朋友不用找文件） */
    private fun notifyUser(context: Context, reason: String, exitTs: Long, uri: Uri?) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val text = buildString {
                append("原因=").append(reason).append("  时间=").append(fmt(exitTs)).append('\n')
                append("点击这条通知可直接分享日志；\n也可在 文件管理 → 下载 里找 ").append(LATEST_NAME)
            }
            var pi: PendingIntent? = null
            if (uri != null) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "TS3 崩溃日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                pi = PendingIntent.getActivity(
                    context,
                    NOTIFY_ID,
                    Intent.createChooser(send, "分享崩溃日志"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val n = NotificationCompat.Builder(context, TsDroidApp.CHANNEL_ID_CONNECTION)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("TS3 上次崩溃已记录")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .apply { pi?.let { setContentIntent(it) } }
                .build()
            nm.notify(NOTIFY_ID, n)
        } catch (t: Throwable) {
            Log.w(TAG, "发通知失败", t)
        }
    }

    private fun appVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName}(${pi.longVersionCode})"
    } catch (_: Throwable) {
        "?"
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }
}
