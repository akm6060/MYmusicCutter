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
import android.widget.RemoteViews
import android.content.BroadcastReceiver
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Keeps the music (or the sound of a video) playing while the app is in the background,
 * with a slim one-line notification: song name, Play / Pause and Exit.
 * Plain custom layout (not the media style), so every phone shows working buttons.
 */
class PlaybackService : Service() {

    interface Control {
        fun toggle()
        fun close()
        /** Stop playback and close the whole app. */
        fun exit() { close() }
        /** Next / previous file in the playlist (earphone, Bluetooth and car buttons). */
        fun skip(dir: Int) {}
    }

    companion object {
        private const val CHANNEL = "playback_line"   // silent, normal priority: shown in full and on the lock screen
        private const val NOTE_ID = 4242
        private const val ACT_TOGGLE = "com.mymusic.cutter.TOGGLE"
        private const val ACT_CLOSE = "com.mymusic.cutter.CLOSE"
        private const val ACT_EXIT = "com.mymusic.cutter.EXIT"

        @Volatile var control: Control? = null
        @Volatile private var title = "MYmusic Cutter"
        @Volatile private var playing = false
        @Volatile private var instance: PlaybackService? = null

        private const val CHANNEL_MIN = "playback_hidden"   // banner switched off: no icon, no lock screen, bottom of the list
        /** Banner switched off by the user (shown as little as Android allows). */
        @Volatile var quiet = false
        /** Silence other apps' notifications while music plays. */
        @Volatile var dnd = false
        private var dndApplied = false
        private var dndBefore = NotificationManager.INTERRUPTION_FILTER_ALL
        private var session: MediaSession? = null

        fun refreshNow() { instance?.refresh() }

        /** Do Not Disturb follows playback: on while playing (if the option is on and allowed), restored afterwards. */
        fun applyDnd(ctx: Context) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) { dndApplied = false; return }
                val want = dnd && playing
                if (want && !dndApplied) {
                    dndBefore = nm.currentInterruptionFilter
                    if (dndBefore == NotificationManager.INTERRUPTION_FILTER_ALL) {   // leave it alone if the user already set a mode
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY); dndApplied = true
                    }
                } else if (!want && dndApplied) {
                    dndApplied = false
                    if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) nm.setInterruptionFilter(dndBefore)
                }
            } catch (_: Exception) {}
        }

        /** Earphone, Bluetooth, car and watch buttons arrive through a media session. */
        private fun syncSession(ctx: Context) {
            try {
                var s = session
                if (s == null) {
                    s = MediaSession(ctx.applicationContext, "MYmusicCutter")
                    s.setCallback(object : MediaSession.Callback() {
                        override fun onMediaButtonEvent(i: Intent): Boolean {
                            val k: KeyEvent = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_KEY_EVENT)) ?: return false
                            if (k.action != KeyEvent.ACTION_DOWN || k.repeatCount > 0) return true
                            when (k.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> control?.toggle()
                                KeyEvent.KEYCODE_MEDIA_NEXT -> control?.skip(1)
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> control?.skip(-1)
                                KeyEvent.KEYCODE_MEDIA_STOP -> control?.close()
                                else -> return false
                            }
                            return true
                        }
                        override fun onPlay() { if (!playing) control?.toggle() }
                        override fun onPause() { if (playing) control?.toggle() }
                        override fun onSkipToNext() { control?.skip(1) }
                        override fun onSkipToPrevious() { control?.skip(-1) }
                    }, Handler(Looper.getMainLooper()))
                    session = s
                }
                s.setPlaybackState(PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build())
                if (!s.isActive) s.isActive = true
            } catch (_: Exception) {}
        }

        fun releaseSession() { try { session?.isActive = false; session?.release() } catch (_: Exception) {}; session = null }

        /** Called whenever playback starts or stops. */
        fun update(ctx: Context, newTitle: String, isPlaying: Boolean) {
            title = newTitle.ifBlank { "MYmusic Cutter" }
            playing = isPlaying
            syncSession(ctx); applyDnd(ctx)
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
        try { nm.deleteNotificationChannel("playback") } catch (_: Exception) {}   // old low-priority channel
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // normal priority so phones do not shrink it to a title-only line, but no sound and no vibration
            val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "One line: what is playing, with Play / Pause and Exit"
            ch.setShowBadge(false)
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.enableLights(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CHANNEL_MIN) == null) {
            val ch = NotificationChannel(CHANNEL_MIN, "Playback (hidden banner)", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false); ch.setSound(null, null); ch.enableVibration(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_SECRET
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
        val toggle = PendingIntent.getBroadcast(this, 1, Intent(this, NotifReceiver::class.java).setAction(ACT_TOGGLE), flags)
        val exit = PendingIntent.getBroadcast(this, 3, Intent(this, NotifReceiver::class.java).setAction(ACT_EXIT), flags)
        val v = RemoteViews(packageName, R.layout.notif_slim)
        v.setTextViewText(R.id.nTitle, title)
        v.setImageViewResource(R.id.nToggle, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        v.setOnClickPendingIntent(R.id.nToggle, toggle)
        v.setOnClickPendingIntent(R.id.nExit, exit)
        return Notification.Builder(this, if (quiet) CHANNEL_MIN else CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentIntent(open)
            .setCustomContentView(v)
            .setCustomBigContentView(v)   // Android may still expand it: then it shows the same single row
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setOngoing(playing)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(if (quiet) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC)
            .build()
    }

    /** Button taps from the notification (a receiver works on every phone, even when the app is in the background). */
    class NotifReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACT_TOGGLE -> control?.toggle()
                ACT_EXIT -> {
                    val c = control
                    if (c != null) c.exit()
                    playing = false; applyDnd(ctx)
                    instance?.let { it.instance0(); }
                    stop(ctx)
                    try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTE_ID) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun instance0() { instance = null; stopForegroundCompat(); stopSelf() }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // the app was swiped away: stop the sound too
        control?.close()
        playing = false; applyDnd(this)
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
