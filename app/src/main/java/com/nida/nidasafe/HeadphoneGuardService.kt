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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent

class HeadphoneGuardService : Service() {
    private lateinit var audioManager: AudioManager
    private var headphoneWasConnected = false
    private var callbackRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private var previousMediaVolume = -1
    private var applyingProtection = false

    private val volumeGuard = object : Runnable {
        override fun run() {
            enforceVolumeProtection()
            handler.postDelayed(this, VOLUME_CHECK_INTERVAL_MS)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isHeadphoneOutput() }) headphoneWasConnected = true
            enforceVolumeProtection()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedHeadphone = removedDevices.any { it.isHeadphoneOutput() }
            if (removedHeadphone && headphoneWasConnected) {
                val stillConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.isHeadphoneOutput() }
                if (!stillConnected) {
                    protectFromSoundLeak()
                    headphoneWasConnected = false
                    updateNotification("イヤホン切断を検知：音量0・停止要求を実行")
                }
            }
            enforceVolumeProtection()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        headphoneWasConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.isHeadphoneOutput() }
        previousMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        callbackRegistered = true
        startForeground(NOTIFICATION_ID, buildNotification("音量急上昇防止・スピーカー音量上限20%を監視中"))
        handler.post(volumeGuard)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Checks volume frequently while the protection service is running.
     * A jump of more than one volume step is rolled back; speaker-only output
     * is additionally capped at 20% of the device's media-volume range.
     */
    private fun enforceVolumeProtection() {
        if (applyingProtection) return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headphonesConnected = outputs.any { it.isHeadphoneOutput() }
        val speakerCap = (maxVolume * SPEAKER_VOLUME_LIMIT_PERCENT / 100).coerceAtLeast(1)

        val target = when {
            !headphonesConnected && outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } &&
                current > speakerCap -> speakerCap
            previousMediaVolume >= 0 && current > previousMediaVolume + MAX_ALLOWED_VOLUME_STEP ->
                previousMediaVolume
            else -> current
        }

        if (target != current) {
            applyingProtection = true
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            } catch (_: SecurityException) {
                // Android/device policy may block changing the media volume.
            } finally {
                applyingProtection = false
            }
            previousMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        } else {
            previousMediaVolume = current
        }
    }

    private fun protectFromSoundLeak() {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            previousMediaVolume = 0
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
            else -> if (Build.VERSION.SDK_INT >= 31) type == AudioDeviceInfo.TYPE_BLE_HEADSET else false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "NidaSafe 保護機能", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "イヤホン切断・急な音量上昇・スピーカー音量を監視" }
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
        handler.removeCallbacks(volumeGuard)
        if (callbackRegistered) audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nidasafe_guard"
        private const val NOTIFICATION_ID = 6101
        private const val VOLUME_CHECK_INTERVAL_MS = 100L
        private const val MAX_ALLOWED_VOLUME_STEP = 1
        private const val SPEAKER_VOLUME_LIMIT_PERCENT = 20
    }
}
