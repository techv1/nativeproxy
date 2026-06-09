package com.techpremium.miniproxy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.color.MaterialColors
import com.techpremium.miniproxy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    // ── State ────────────────────────────────────────────────────────────────
    private var isProxyRunning = false

    // ── Notification permission launcher (Android 13+) ───────────────────────
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                doStartProxy()
            } else {
                Toast.makeText(
                    this,
                    "Notification permission is required to run the proxy in the background.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // ── BroadcastReceiver: receives status updates from ProxyService ──────────
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ProxyService.ACTION_STATUS_RUNNING -> setRunningUi(true)
                ProxyService.ACTION_STATUS_STOPPED -> setRunningUi(false)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = getSharedPreferences("proxy_prefs", Context.MODE_PRIVATE)

        restorePrefs()
        setupClickListeners()
        syncRunningStateFromService()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(ProxyService.ACTION_STATUS_RUNNING)
            addAction(ProxyService.ACTION_STATUS_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    private fun restorePrefs() {
        binding.etBindIp.setText(prefs.getString("bind_ip", "0.0.0.0"))
        binding.etPort.setText(prefs.getString("port", "8080"))
    }

    private fun setupClickListeners() {
        binding.btnToggle.setOnClickListener {
            if (isProxyRunning) {
                stopProxy()
            } else {
                validateAndStartProxy()
            }
        }
    }

    private fun syncRunningStateFromService() {
        // Check if service is already running (e.g. after activity recreation)
        setRunningUi(ProxyService.isRunning)
    }

    private fun setRunningUi(running: Boolean) {
        isProxyRunning = running

        if (running) {
            binding.btnToggle.text = getString(R.string.stop_proxy)
            binding.btnToggle.setBackgroundTintList(
                ContextCompat.getColorStateList(this, R.color.color_error)
            )
            binding.tvStatus.text = getString(R.string.status_running)
            binding.tvStatus.setTextColor(getColor(R.color.color_status_running))
            setStatusDotColor(getColor(R.color.color_status_running))
            binding.tvConnectionCount.visibility = View.VISIBLE
            binding.etBindIp.isEnabled = false
            binding.etPort.isEnabled = false
        } else {
            binding.btnToggle.text = getString(R.string.start_proxy)
            binding.btnToggle.setBackgroundTintList(
                ContextCompat.getColorStateList(this, R.color.color_primary)
            )
            binding.tvStatus.text = getString(R.string.status_idle)
            binding.tvStatus.setTextColor(getColor(R.color.color_status_idle))
            setStatusDotColor(getColor(R.color.color_status_idle))
            binding.tvConnectionCount.visibility = View.GONE
            binding.etBindIp.isEnabled = true
            binding.etPort.isEnabled = true
        }
    }

    private fun setStatusDotColor(color: Int) {
        val bg = binding.statusDot.background as? GradientDrawable
            ?: GradientDrawable().also { binding.statusDot.background = it }
        bg.setColor(color)
    }

    // ── Proxy control ─────────────────────────────────────────────────────────
    private fun validateAndStartProxy() {
        val ip = binding.etBindIp.text?.toString()?.trim() ?: ""
        val portStr = binding.etPort.text?.toString()?.trim() ?: ""

        if (ip.isEmpty()) {
            binding.tilBindIp.error = "IP address required"
            return
        }
        binding.tilBindIp.error = null

        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            binding.tilPort.error = "Enter a valid port (1–65535)"
            return
        }
        binding.tilPort.error = null

        // Save preferences
        prefs.edit().putString("bind_ip", ip).putString("port", portStr).apply()

        // Check / request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        doStartProxy()
    }

    private fun doStartProxy() {
        val ip = prefs.getString("bind_ip", "0.0.0.0") ?: "0.0.0.0"
        val port = prefs.getString("port", "8080") ?: "8080"

        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
            putExtra(ProxyService.EXTRA_BIND_IP, ip)
            putExtra(ProxyService.EXTRA_PORT, port.toIntOrNull() ?: 8080)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopProxy() {
        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        startService(intent)
    }
}
