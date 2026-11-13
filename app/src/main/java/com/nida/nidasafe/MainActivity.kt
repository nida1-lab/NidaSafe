package com.nida.nidasafe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val preferences by lazy {
        getSharedPreferences(HeadphoneGuardService.PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateStatus()
    }

    private fun startProtection() {
        try {
            val intent = Intent(this, HeadphoneGuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            status.text = "保護を開始しています…"
        } catch (e: RuntimeException) {
            status.text = "保護を開始できませんでした。アプリの設定を確認してください。"
        }
    }

    private fun updateStatus() {
        val running = preferences.getBoolean(HeadphoneGuardService.KEY_SERVICE_RUNNING, false)
        val blocking = preferences.getBoolean(HeadphoneGuardService.KEY_BLOCK_SPEAKER, false)
        status.text = when {
            !running -> "保護停止中：開始ボタンから有効化してください"
            blocking -> "保護中：スピーカー再生禁止 ON"
            else -> "保護中：音量急上昇防止・スピーカー上限20%"
        }
    }

    private fun render() {
        val dark = 0xFF17191D.toInt()
        val muted = 0xFF5A6070.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(32))
            setBackgroundColor(0xFFF7F8FA.toInt())
        }
        val title = TextView(this).apply {
            text = "NidaSafe"
            textSize = 32f
            setTextColor(dark)
            setPadding(0, 0, 0, dp(6))
        }
        val subtitle = TextView(this).apply {
            text = "Speaker Guard • 音漏れ防止"
            textSize = 16f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(20))
        }
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(dark)
            setPadding(0, dp(8), 0, dp(22))
        }
        val speakerToggle = Switch(this).apply {
            text = "スピーカー再生を禁止"
            textSize = 18f
            setTextColor(dark)
            isChecked = preferences.getBoolean(
                HeadphoneGuardService.KEY_BLOCK_SPEAKER, false
            )
            setPadding(0, dp(12), 0, dp(10))
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit()
                    .putBoolean(HeadphoneGuardService.KEY_BLOCK_SPEAKER, enabled)
                    .apply()
                if (enabled) {
                    // One tap both enables strict mode and starts the monitor.
                    startProtection()
                } else {
                    updateStatus()
                }
            }
        }
        val toggleHelp = TextView(this).apply {
            text = "ONにすると、イヤホン・USB DAC等に出力されていない間、" +
                "全アプリ共通のメディア音量を0にし、再生停止を要求します。" +
                "NidaPlayer側の更新は不要です。"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(18))
        }
        val start = Button(this).apply {
            text = "保護を開始"
            setOnClickListener { startProtection() }
        }
        val stop = Button(this).apply {
            text = "保護を停止"
            setOnClickListener {
                stopService(Intent(this@MainActivity, HeadphoneGuardService::class.java))
                preferences.edit()
                    .putBoolean(HeadphoneGuardService.KEY_SERVICE_RUNNING, false)
                    .apply()
                updateStatus()
            }
        }
        val info = TextView(this).apply {
            text = "従来の保護：イヤホン切断時はメディア音量0＋停止要求。" +
                "禁止モードOFFならスピーカー音量上限20%。急な音量上昇も抑制します。"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(18), 0, dp(10))
        }
        val warning = TextView(this).apply {
            text = "注意：Androidでは他アプリの音を完全には遮断できません。" +
                "一瞬の音漏れやアプリ側の停止拒否が起こる可能性があります。" +
                "Bluetoothスピーカーはイヤホンと区別できない場合があります。" +
                "通知音・アラーム・通話音量は変更しません。" +
                "安全のため、イヤホン再接続時にも自動音量復元・自動再生はしません。"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(12), 0, dp(14))
        }
        root.addView(title, ViewGroup.LayoutParams(-1, -2))
        root.addView(subtitle, ViewGroup.LayoutParams(-1, -2))
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(speakerToggle, ViewGroup.LayoutParams(-1, -2))
        root.addView(toggleHelp, ViewGroup.LayoutParams(-1, -2))
        root.addView(start, ViewGroup.LayoutParams(-1, -2))
        root.addView(stop, ViewGroup.LayoutParams(-1, -2))
        root.addView(info, ViewGroup.LayoutParams(-1, -2))
        root.addView(warning, ViewGroup.LayoutParams(-1, -2))
        setContentView(ScrollView(this).apply {
            addView(root)
            isFillViewport = true
        })
        updateStatus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
