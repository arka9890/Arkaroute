package com.omniroute.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omniroute.app.config.ConfigStore
import com.omniroute.app.config.KeystoreCryptoEngine

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val store = ConfigStore(context.applicationContext.filesDir, KeystoreCryptoEngine())
        val (_, meta) = store.load()
        if (BootDecision.shouldStart(meta)) {
            val startIntent = Intent(context, GatewayService::class.java).apply {
                action = GatewayService.ACTION_START
            }
            context.startForegroundService(startIntent)
        }
    }
}
