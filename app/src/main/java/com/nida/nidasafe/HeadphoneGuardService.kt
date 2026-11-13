package com.nida.nidasafe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Protects the media stream only. Calls, alarms and notification stream volumes
 * are deliberately not changed. This is best-effort, not a system audio firewall.
 */
class HeadphoneGuardService : Service() {
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var callbackRegistered = false
    private var previousMediaVolume = -1
    private var lastPersonalRoute = false
    private var lastSpeakerBlocked = false
    private var lastPauseRequestMs = 0L
    private var applyingProtection = false

    private val volumeGuard = object : Runnable {
        override fun run() {
            enforceVolumeProtection()
            handler.postDelayed(this, VOLUME_CHECK_INTERVAL_MS)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            enforceVolumeProtection()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            enforceVolumeProtection()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        lastPersonalRoute = hasPersonalMediaRoute()
        previousMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // A foreground service must become foreground promptly after user start.
        startForeground(NOTIFICATION_ID, buildNotification("音声出力とメディア音量を監視中"))
        preferences.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply()

        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        callbackRegistered = true
        handler.post(volumeGuard)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post { enforceVolumeProtection() }
        return START_STICKY
    }

    private fun enforceVolumeProtection() {
        if (applyingProtection || !::audioManager.isInitialized) return

        val personalRoute = hasPersonalMediaRoute()
        val strictMode = preferences.getBoolean(KEY_BLOCK_SPEAKER, false)
        val blocked = strictMode && !personalRoute

        // Always protect a wired/Bluetooth/USB disconnect, including when strict
        // mode is disabled, retaining NidaSafe's existing behavior.
        if (lastPersonalRoute && !personalRoute) {
            setMediaVolumeSafely(0)
            requestMediaPause()
        } else if (!lastPersonalRoute && personalRoute) {
            // A newly connected headset should not be stuck at the old 0 step.
            // Never restore volume or automatically resume playback.
            previousMediaVolume = -1
        }
        lastPersonalRoute = personalRoute

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val speakerCap = (maxVolume * SPEAKER_VOLUME_LIMIT_PERCENT / 100).coerceAtLeast(1)

        val target = when {
            blocked -> 0
            !personalRoute && current > speakerCap -> speakerCap
            previousMediaVolume >= 0 &&
                current > previousMediaVolume + MAX_ALLOWED_VOLUME_STEP -> previousMediaVolume
            else -> current
        }
        if (target != current) setMediaVolumeSafely(target)
        else previousMediaVolume = current

        if (blocked && (!lastSpeakerBlocked || audioManager.isMusicActive)) {
            requestMediaPause()
        }
        if (blocked != lastSpeakerBlocked) {
            updateNotification(
                if (blocked) "Speaker Guard：スピーカーをミュート中"
                else "保護中：イヤホン・USB DAC等の出力を確認"
            )
        }
        lastSpeakerBlocked = blocked
    }

    /**
     * API 33+: check the predicted route for media, not merely that headphones
     * are plugged in. A connected headset can coexist with speaker routing.
     * Older devices (or a failed routing query) fall back to connected outputs.
     */
    private fun hasPersonalMediaRoute(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val routed = try {
                audioManager.getAudioDevicesForAttributes(mediaAttributes)
            } catch (_: RuntimeException) {
                emptyList<AudioDeviceInfo>()
            }
            if (routed.isNotEmpty()) {
                // Mixed output containing the built-in speaker is not accepted.
                return routed.all { it.isPersonalOutput() }
            }
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.isPersonalOutput() }
    }

    private fun AudioDeviceInfo.isPersonalOutput(): Boolean {
        if (!isSink) return false
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
            AudioDeviceInfo.TYPE_HEARING_AID -> Build.VERSION.SDK_INT >= 28
            else -> Build.VERSION.SDK_INT >= 31 &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    private fun setMediaVolumeSafely(value: Int) {
        if (applyingProtection) return
        applyingProtection = true
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        } catch (_: SecurityException) {
            // Some device policies / DND settings prohibit stream changes.
        } catch (_: RuntimeException) {
            // Do not crash the safety monitor on vendor audio stack errors.
        } finally {
            previousMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            applyingProtection = false
        }
    }

    private fun requestMediaPause() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPauseRequestMs < PAUSE_REQUEST_INTERVAL_MS) return
        lastPauseRequestMs = now
        try {
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            )
        } catch (_: RuntimeException) {
            // Other players can ignore or reject media keys.
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "NidaSafe 音漏れ防止", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "イヤホン切断とメディアのスピーカー出力を監視"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NidaSafe")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        handler.removeCallbacks(volumeGuard)
        if (callbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        }
        preferences.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS_NAME = "nidasafe_preferences"
        const val KEY_BLOCK_SPEAKER = "speaker_guard_enabled"
        const val KEY_SERVICE_RUNNING = "protection_service_running"
        private const val CHANNEL_ID = "nidasafe_guard"
        private const val NOTIFICATION_ID = 6101
        private const val VOLUME_CHECK_INTERVAL_MS = 100L
        private const val PAUSE_REQUEST_INTERVAL_MS = 1_200L
        private const val MAX_ALLOWED_VOLUME_STEP = 1
        private const val SPEAKER_VOLUME_LIMIT_PERCENT = 20
    }
}
