package com.example.ytdlpapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvNotifStatus: TextView
    private lateinit var btnNotif: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnBattery: Button

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Ditolak dan sistem tidak mau bertanya lagi -> arahkan ke pengaturan aplikasi
            if (!granted && Build.VERSION.SDK_INT >= 33 &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                Perms.openNotificationSettings(this)
            }
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Pengaturan"

        tvNotifStatus = findViewById(R.id.tvNotifStatus)
        btnNotif = findViewById(R.id.btnNotif)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnBattery = findViewById(R.id.btnBattery)

        btnNotif.setOnClickListener {
            when {
                Perms.notificationsEnabled(this) -> Perms.openNotificationSettings(this)
                Build.VERSION.SDK_INT >= 33 ->
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> Perms.openNotificationSettings(this)
            }
        }

        btnBattery.setOnClickListener {
            if (Perms.batteryUnrestricted(this)) Perms.openBatterySettings(this)
            else Perms.requestBattery(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val notif = Perms.notificationsEnabled(this)
        tvNotifStatus.text = if (notif) "Status: Diizinkan" else "Status: Belum diizinkan"
        btnNotif.text = if (notif) "Buka pengaturan notifikasi" else "Izinkan notifikasi"

        val battery = Perms.batteryUnrestricted(this)
        tvBatteryStatus.text = if (battery) "Status: Tanpa batas (diizinkan)" else "Status: Belum diizinkan"
        btnBattery.text = if (battery) "Buka pengaturan baterai" else "Izinkan penggunaan baterai tanpa batas"
    }
}
