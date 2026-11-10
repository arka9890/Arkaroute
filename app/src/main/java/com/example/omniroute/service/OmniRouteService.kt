package com.example.omniroute.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.omniroute.AppPrefs
import com.example.omniroute.AppLog
import com.example.omniroute.MainActivity
import com.example.omniroute.NativeBridge
import com.example.omniroute.ProotRuntime
import org.json.JSONObject
import java.io.File

class OmniRouteService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var proot: Process? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startDaemon()
            ACTION_STOP -> stopDaemon()
            ACTION_RESTART -> restartDaemon()
        }
        return START_STICKY
    }

    private fun startDaemon() {
        val prefs = AppPrefs.snapshot(this)
        acquireWakeLock()
        if (ProotRuntime.isInstalled(this)) {
            startProot(prefs.port)
            startForeground(NOTIFICATION_ID, notification("OmniRoute online", "PROot omniroute :${prefs.port}"))
        } else {
            NativeBridge.start(prefs.port, prefs.upstream)
            startForeground(NOTIFICATION_ID, notification("OmniRoute online", "Gateway ${prefs.port} • ${prefs.upstream}"))
        }
    }

    private fun startProot(port: Int) {
        stopProot()
        val cmd = ProotRuntime.command(this, port)
        val pb = ProcessBuilder(cmd)
        pb.environment()["PROOT_LOADER"] = ProotRuntime.loaderBinary(this) ?: ""
        pb.environment()["PROOT_TMP_DIR"] = File(filesDir, "proot/tmp").apply { mkdirs() }.absolutePath
        pb.environment()["PROOT_NO_SECCOMP"] = "1"
        pb.environment()["LD_LIBRARY_PATH"] = File(filesDir, "proot/lib").apply { mkdirs() }.absolutePath
        pb.redirectOutput(File(filesDir, "proot/omniroute.log"))
        pb.redirectErrorStream(true)
        proot = pb.start()
        AppLog.log("INFO", "proot omniroute started on port $port")
    }

    private fun stopProot() {
        proot?.destroy()
        proot = null
    }

    private fun restartDaemon() {
        val prefs = AppPrefs.snapshot(this)
        acquireWakeLock()
        if (ProotRuntime.isInstalled(this)) {
            stopProot()
            startProot(prefs.port)
            startForeground(NOTIFICATION_ID, notification("OmniRoute restarted", "PROot omniroute :${prefs.port}"))
        } else {
            NativeBridge.restart(prefs.port, prefs.upstream)
            startForeground(NOTIFICATION_ID, notification("OmniRoute restarted", "Gateway ${prefs.port} • ${prefs.upstream}"))
        }
    }

    private fun stopDaemon() {
        stopProot()
        NativeBridge.stop()
        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniRoute::DaemonWakeLock").apply { acquire() }
    }

    private fun notification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pending)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "OmniRoute Daemon", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Background OmniRoute gateway status and controls"
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.omniroute.action.START"
        const val ACTION_STOP = "com.example.omniroute.action.STOP"
        const val ACTION_RESTART = "com.example.omniroute.action.RESTART"
        private const val CHANNEL_ID = "omniroute_daemon"
        private const val NOTIFICATION_ID = 42001

        fun status(): String = NativeBridge.statusJson()

        fun toggle(context: Context, online: Boolean) {
            val intent = Intent(context, OmniRouteService::class.java).apply { action = if (online) ACTION_STOP else ACTION_START }
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}