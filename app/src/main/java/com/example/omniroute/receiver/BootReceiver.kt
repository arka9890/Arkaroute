package com.example.omniroute.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.omniroute.AppPrefs
import com.example.omniroute.service.OmniRouteService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && AppPrefs.snapshot(context).autoStart) {
            val service = Intent(context, OmniRouteService::class.java).apply { action = OmniRouteService.ACTION_START }
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
        }
    }
}