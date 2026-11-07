package com.mymusic.cutter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Keeps the music (or the sound of a video) playing while the app is in the background,
 * with a small notification: Play / Pause and Close.
 */
class PlaybackService : Service() {

    interface Control {
        fun toggle()
        fun close()
        /** Stop playback and close the whole app. */
        fun exit() { close() }
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTE_ID = 4242
        private const val ACT_TOGGLE = "com.mymusic.cutter.TOGGLE"
        private const val ACT_CLOSE = "com.mymusic.cutter.CLOSE"
        private const val ACT_EXIT = "com.mymusic.cutter.EXIT"

        @Volatile var control: Control? = null
        @Volatile private var title = "MYmusic Cutter"
        @Volatile private var playing = false
        @Volatile private var instance: PlaybackService? = null

        /** Called whenever playback starts or stops. */
        fun update(ctx: Context, newTitle: String, isPlaying: Boolean) {
            title = newTitle.ifBlank { "MYmusic Cutter" }
            playing = isPlaying
            val s = instance
            if (s != null) { s.refresh(); return }
            if (!isPlaying) return
            try {
                ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PlaybackService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shows what is playing, with Play / Pause"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // always go to the foreground first (required within a few seconds of starting)
        goForeground()
        when (intent?.action) {
            ACT_TOGGLE -> control?.toggle()
            ACT_EXIT -> {
                control?.exit()
                instance = null
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACT_CLOSE -> {
                control?.close()
                instance = null
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        instance = this
        val n = build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTE_ID, n)
        } catch (_: Exception) {
        }
    }

    fun refresh() {
        try { getSystemService(NotificationManager::class.java).notify(NOTE_ID, build()) } catch (_: Exception) {}
    }

    private fun stopForegroundCompat() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    private fun build(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val toggle = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(ACT_TOGGLE), flags)
        val exit = PendingIntent.getService(this, 3, Intent(this, PlaybackService::class.java).setAction(ACT_EXIT), flags)
        val playPause = Notification.Action.Builder(
            Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "Pause" else "Play", toggle
        ).build()
        val exitAct = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Exit", exit
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(playPause)
            .addAction(exitAct)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // the app was swiped away: stop the sound too
        control?.close()
        instance = null
        stopForegroundCompat()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
