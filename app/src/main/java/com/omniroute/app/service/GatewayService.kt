package com.omniroute.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.omniroute.app.R
import com.omniroute.app.bridge.NativeGateway
import com.omniroute.app.config.ConfigStore
import com.omniroute.app.config.KeystoreCryptoEngine
import org.json.JSONObject
import java.util.concurrent.Executors

class GatewayService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: ConfigStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(applicationContext.filesDir, KeystoreCryptoEngine())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> executor.execute { stopServerAndCleanup() }
                else -> {
                    startForeground(NOTIF_ID, buildNotification(getString(R.string.service_starting)))
                    executor.execute { startServer() }
                }
            }
        } catch (e: Exception) {
            // Never throw out of onStartCommand.
            try {
                stopSelf()
            } catch (ignored: Exception) {
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            NativeGateway.stopServer()
        } catch (ignored: Exception) {
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun startServer() {
        try {
            if (NativeGateway.isRunning()) {
                showActiveNotification()
                return
            }
            val configJson = store.coreJson()
            val config = JSONObject(configJson)
            val bind = config.optString("bind", "127.0.0.1")
            val port = config.optInt("port", 20128)
            val result = JSONObject(NativeGateway.startServer(configJson))
            if (result.optBoolean("ok", false)) {
                startForeground(NOTIF_ID, buildNotification(getString(R.string.service_active, bind, port)))
            } else {
                val error = result.optString("error", getString(R.string.service_unknown_error))
                startForeground(NOTIF_ID, buildNotification(getString(R.string.service_failed, error)))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (e: Exception) {
            val error = e.message ?: getString(R.string.service_unknown_error)
            try {
                startForeground(NOTIF_ID, buildNotification(getString(R.string.service_failed, error)))
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (ignored: Exception) {
            }
            stopSelf()
        }
    }

    private fun stopServerAndCleanup() {
        try {
            NativeGateway.stopServer()
        } catch (ignored: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showActiveNotification() {
        val config = JSONObject(store.coreJson())
        val bind = config.optString("bind", "127.0.0.1")
        val port = config.optInt("port", 20128)
        startForeground(NOTIF_ID, buildNotification(getString(R.string.service_active, bind, port)))
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.omniroute.app.ACTION_START"
        const val ACTION_STOP = "com.omniroute.app.ACTION_STOP"
        const val CHANNEL_ID = "omniroute_status"
        const val NOTIF_ID = 1
    }
}
