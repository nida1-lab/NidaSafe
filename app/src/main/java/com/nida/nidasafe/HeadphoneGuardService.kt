package com.nida.nidasafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent

class HeadphoneGuardService : Service() {
    private lateinit var audioManager: AudioManager
    private var headphoneWasConnected = false
    private var callbackRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isHeadphoneOutput() }) headphoneWasConnected = true
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedHeadphone = removedDevices.any { it.isHeadphoneOutput() }
            if (!removedHeadphone || !headphoneWasConnected) return

            val stillConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.isHeadphoneOutput() }
            if (!stillConnected) {
                protectFromSoundLeak()
                headphoneWasConnected = false
                updateNotification("イヤホン切断を検知：音量0・停止要求を実行")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        headphoneWasConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.isHeadphoneOutput() }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        callbackRegistered = true
        startForeground(NOTIFICATION_ID, buildNotification("イヤホン切断を監視中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun protectFromSoundLeak() {
        // Lower the media volume first, then ask the active media session to pause.
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: SecurityException) {
            // Device policy or OS restrictions may block volume changes.
        }
        try {
            val now = android.os.SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        } catch (_: RuntimeException) {
            // Some apps do not accept global media key events.
        }
    }

    private fun AudioDeviceInfo.isHeadphoneOutput(): Boolean {
        if (!isSink) return false
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
            else -> if (Build.VERSION.SDK_INT >= 31) {
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } else false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "NidaSafe 保護機能", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "イヤホン切断時の音漏れ防止監視" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("NidaSafe")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("NidaSafe")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        if (callbackRegistered) audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nidasafe_guard"
        private const val NOTIFICATION_ID = 6101
    }
}
