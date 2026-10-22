package com.md3music.md3music

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import com.google.common.collect.ImmutableList
import com.ryanheise.just_audio.AudioPlayer

/**
 * 媒体3 会话承载服务（方案 B 阶段1：媒体3 通知栏上线）。
 *
 * 职责：把 just_audio fork 自建的 androidx.media3.session.MediaSession 归入本服务，
 * 由 media3 的 MediaNotificationManager + DefaultMediaNotificationProvider 生成系统
 * now playing 通知（通知栏可见、可控制播放）。仅作会话承载，不承担播放/焦点逻辑。
 */
@OptIn(UnstableApi::class)
@SuppressLint("UnsafeOptInUsageError")
class MD3MusicMediaSessionService : MediaSessionService() {

    /** 连车唤醒：外部 bind 且无活跃会话时自动拉起+续播（咪咕式），默认开 */
    private var autoWakeEnabled = true
    /** 上次触发改唤醒的时刻（节流防重试风暴） */
    private var lastCarWakeAt = 0L

    override fun onCreate() {
        // 所有机型都安装本 App 的媒体通知 Provider：底层仍是 media3 默认实现，额外做两件事
        // —— 统一设单色小图标（所有机型保留）；仅 Flyme 系把歌词写进媒体通知的 tickerText。
        // 必须在 onCreate() 返回前设置（fork 源码 MediaSessionService:594 的约束）。
        setMediaNotificationProvider(
            Md3MediaNotificationProvider(
                DefaultMediaNotificationProvider.Builder(applicationContext).build(),
                applicationContext
            )
        )
        super.onCreate()
        // 注册为 fork 的会话 host：fork 创建/已有活跃会话后 addSession 到本服务渲染通知
        AudioPlayer.setMediaSessionServiceHost(this)
        // MD3Music fork v21（连车拉起）：读取外部绑定时自动拉起+续播的开关
        autoWakeEnabled = try {
            getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                .getBoolean("flutter.settings_autoplay_on_car_connect", true)
        } catch (_: Exception) { true }
    }

    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? {
        // 返回 fork 当前活跃的媒体3会话；未初始化时拒绝连接
        return AudioPlayer.getActiveMediaSession()
    }

    /**
     * 本 App 的媒体通知 Provider：委托给 media3 默认实现，额外做两件事 ——
     * 1) 统一设置单色小图标（所有机型）；
     * 2) 仅 Flyme 系（FlymeLyricBridge.isSupported()）把当前歌词原地写进媒体通知的
     *    tickerText 并置 Flyme 私有 flag 位。非 Flyme 机型（vivo/iQOO 等）走纯透传，
     *    不接触 Notification 对象，避免打断原子随身听对媒体通知的读取
     *    （表现为卡片出现但无封面/无歌词/无进度）。
     *
     * fork 的 MediaSessionService 只有 onUpdateNotification(MediaSession[, boolean]) 两个
     * 重载，拿不到 Notification 对象与通知 id，所以只能在 provider 这一层挂钩。
     */
    private class Md3MediaNotificationProvider(
        private val delegate: DefaultMediaNotificationProvider,
        private val context: Context
    ) : MediaNotification.Provider {

        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            delegate.setSmallIcon(R.drawable.ic_launcher_monochrome)
            // 按机型选择：非 Flyme 机型直接原样透传 Callback，语义与 media3 默认 Provider
            // 完全一致（只多了上面那行小图标），绝不包装/改写通知对象。
            if (!FlymeLyricBridge.isSupported()) {
                return delegate.createNotification(
                    mediaSession, customLayout, actionFactory, onNotificationChangedCallback
                )
            }
            // fork 的 MediaNotificationManager 会把这个 Callback 直接交给 delegate，
            // 封面图异步加载完成后它会被触发并绕过本包装层直接重发通知（见
            // MediaNotificationManager:167-176 → onNotificationUpdated → updateNotificationInternal）。
            // 若不拦这一道，每次 artwork 刷新都会把 tickerText 与 Flyme flags 冲掉。
            val wrapped = MediaNotification.Provider.Callback { mediaNotification ->
                // 只贴歌词、不缓存：这条路径受 Media3 序列号守卫约束，对象可能被丢弃。
                // 若在此 attach()，会缓存一个 Media3 已经扔掉的旧 Notification，
                // 之后每句歌词都把它重发出来 —— 状态栏翻回上一首且永不自愈。
                FlymeLyricBridge.decorateOnly(mediaNotification.notification)
                onNotificationChangedCallback.onNotificationChanged(mediaNotification)
            }
            val result = delegate.createNotification(
                mediaSession, customLayout, actionFactory, wrapped
            )
            FlymeLyricBridge.attach(context, result.notificationId, result.notification)
            return result
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean = delegate.handleCustomCommand(session, action, extras)
    }

    override fun onBind(intent: Intent?): IBinder? {
        // MD3Music fork: 原子随身听（vivomusicmix）以 vivo action 绑定本服务。
        // media3 onBind 只认 media3 / android.media.browse.MediaBrowserService 两个 action，
        // vivo action 落 default 分支返回 null → 绑定失败 → 原子拿不到 session
        // （无歌词、无封面、无进度条）。把 vivo action 映射到 legacy MediaBrowserService
        // 路径（返回 legacy browser binder，原子经 MediaControllerCompat 读取）。
        maybeWakeForCarConnect(intent?.action)
        if (intent?.action == "com.vivo.musicwidgetmix.support.service") {
            return super.onBind(
                Intent("android.media.browse.MediaBrowserService")
            )
        }
        return super.onBind(intent)
    }

    /**
     * MD3Music fork v21（连车拉起，咪咕式）：外部 bind 且当前没有任何活跃媒体会话，
     * 等价于"车机来了但 App 还什么都没有"——走既有线控唤醒链拉起进程并自动续播。
     * 排除项：原子随身听的 vivo 专用 action（进程活着时它常驻绑定，放行会变成变相
     * 开机自播）；15s 节流防重试风暴；flutter.settings_autoplay_on_car_connect 默认
     * 开可关。拉起链：startForegroundService(AudioPlaybackService, ACTION_MEDIA_BUTTON,
     * command="play", wakeOrigin=car) → ensureForeground → headless 引擎恢复 →
     * playerReady 后派发 play（同时头侧按 wakeOrigin 放开媒体会话创建，车机卡片
     * 才有内容）。
     */
    private fun maybeWakeForCarConnect(action: String?) {
        if (action == "com.vivo.musicwidgetmix.support.service") return
        if (!autoWakeEnabled) return
        // MD3Music fork v26：标准 MediaSessionService 绑定在手机本地也会发生
        // （系统媒体框架每次进程冷启动都会常规 bind 一次），单靠排除 vivo action
        // 会把普通打开 App 误判成连车——自动拉起恢复后撞上凭证加载窗口，误弹
        // 「请先登录」。改与卡片深链同一判据：存在投屏虚拟屏才算真连车。车机
        // 端绑定若早于投屏建立会错过首次，由车机重连再触发；留下跳过原因便于核对。
        val dm = getSystemService(DisplayManager::class.java)
        if (dm == null || dm.displays.none { it.displayId != Display.DEFAULT_DISPLAY }) {
            Log.i(
                "CarAutoResume",
                "external bind action=$action skipped: no projected display (local system bind)"
            )
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCarWakeAt < 15_000L) return
        if (AudioPlayer.getActiveMediaSession() != null) return
        lastCarWakeAt = now
        Log.i(
            "CarAutoResume",
            "external bind action=$action without active session → wake & auto play"
        )
        val svc = Intent(this, AudioPlaybackService::class.java).apply {
            this.action = AudioPlaybackService.ACTION_MEDIA_BUTTON
            putExtra(AudioPlaybackService.EXTRA_MEDIA_COMMAND, "play")
            putExtra(AudioPlaybackService.EXTRA_WAKE_ORIGIN, "car")
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (t: Throwable) {
            Log.w("CarAutoResume", "wake dispatch failed", t)
        }
    }

    override fun onDestroy() {
        AudioPlayer.setMediaSessionServiceHost(null)
        super.onDestroy()
    }

    // 阶段1 暂时不接管自定义通知的停止语义；播放停止时 media3 默认会取消 now playing 通知
    // 并让本服务退回后台，无需额外处理。onTaskRemoved 使用默认实现。
}
