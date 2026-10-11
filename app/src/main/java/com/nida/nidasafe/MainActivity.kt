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
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val title = TextView(this).apply {
            text = "NidaSafe"
            textSize = 32f
            setTextColor(0xFF111111.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "イヤホンが外れたら、音漏れを防ぐ"
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        status = TextView(this).apply {
            text = "保護機能は停止中です"
            textSize = 16f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        val start = Button(this).apply {
            text = "保護を開始"
            setOnClickListener {
                val intent = Intent(this@MainActivity, HeadphoneGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
                status.text = "保護機能を起動しました。通知から状態を確認できます。"
            }
        }
        val stop = Button(this).apply {
            text = "保護を停止"
            setOnClickListener {
                stopService(Intent(this@MainActivity, HeadphoneGuardService::class.java))
                status.text = "保護機能は停止中です"
            }
        }
        val note = TextView(this).apply {
            text = "イヤホン切断時にメディア音量を0にし、再生中アプリへ一時停止を要求します。Androidやアプリ側の制約により、停止動作は保証されない場合があります。"
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setPadding(0, 24, 0, 0)
        }
        root.addView(title, ViewGroup.LayoutParams(-1, -2))
        root.addView(subtitle, ViewGroup.LayoutParams(-1, -2))
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(start, ViewGroup.LayoutParams(-1, -2))
        root.addView(stop, ViewGroup.LayoutParams(-1, -2))
        root.addView(note, ViewGroup.LayoutParams(-1, -2))
        setContentView(root)
    }
}
