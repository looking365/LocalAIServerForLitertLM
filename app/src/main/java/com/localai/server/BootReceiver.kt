package com.localai.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the AI server automatically when the device boots,
 * if the user has enabled "Auto-start on boot" in settings.
 *
 * The server will start, then auto-load the last used model.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val settings = SettingsManager(context)
        if (!settings.autoStartOnBoot) {
            Log.d(TAG, "Boot received but auto-start disabled")
            return
        }

        val lastModel = settings.lastModelPath
        if (lastModel.isBlank()) {
            Log.d(TAG, "Boot auto-start: no last model saved")
            return
        }

        Log.d(TAG, "Boot auto-start: launching server with model $lastModel")

        // Start the foreground service - it will auto-load the model
        val serviceIntent = Intent(context, com.localai.server.service.AiServerService::class.java).apply {
            putExtra(EXTRA_AUTO_LOAD_MODEL, lastModel)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        const val EXTRA_AUTO_LOAD_MODEL = "auto_load_model_path"
    }
}
