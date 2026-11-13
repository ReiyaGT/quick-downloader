package com.example.ytdlpapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity(), DownloadState.Listener {

    private lateinit var etUrl: EditText
    private lateinit var rbAudio: RadioButton
    private lateinit var btnDownload: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnCancel: Button
    private lateinit var btnOpen: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    @Volatile private var ready = false
    @Volatile private var busy = false          // update yt-dlp sedang berjalan
    @Volatile private var appUpdating = false   // cek/unduh update aplikasi sedang berjalan

    private val releaseApi = "https://api.github.com/repos/ReiyaGT/quick-downloader/releases/latest"

    private val logBuf = StringBuilder()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var loadingActive = false
    private var loadingDots = 0
    private val loadingText = "Menyiapkan ytdlp+ ffmpeg"
    private val maxDots = 5

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Setelah notifikasi (diizinkan atau tidak), lanjut ke izin baterai
            askBatteryUnrestricted()
        }

    private val loadingTick = object : Runnable {
        override fun run() {
            if (!loadingActive) return
            renderLog(loadingText + ".".repeat(loadingDots))
            loadingDots = (loadingDots + 1) % maxDots // 0..4, titik penuh (5) hanya saat selesai
            uiHandler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        rbAudio = findViewById(R.id.rbAudio)
        btnDownload = findViewById(R.id.btnDownload)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnCancel = findViewById(R.id.btnCancel)
        btnOpen = findViewById(R.id.btnOpen)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)

        btnDownload.setOnClickListener { startDownload() }
        btnUpdate.setOnClickListener { startUpdate() }
        btnCancel.setOnClickListener { cancelDownload() }
        btnOpen.setOnClickListener { openLastFile() }
        btnCheckUpdate.setOnClickListener { checkAppUpdate() }

        handleShareIntent(intent)

        log("Quick Downloader by Resi Surya")
        log("Aplikasi ini merupakan Fork dari yt-dlp. Pengembangan dilakukan hanya untuk educational only")
        log("---")
        log("Tekan tombol Update YT-DLP untuk pembaruan sistem")

        initLibrary()
        maybeShowFirstRunPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        DownloadState.attach(this)
    }

    override fun onStop() {
        DownloadState.detach(this)
        super.onStop()
    }

    // ---------- Menu (ikon gerigi) ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun handleShareIntent(i: Intent?) {
        // Link yang di-share dari app lain (YouTube, dll) ke app ini
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { etUrl.setText(it) }
        }
    }

    // ---------- Izin pertama kali ----------

    private fun maybeShowFirstRunPermissions() {
        if (prefs.getBoolean("perm_prompted", false)) return
        // Ditandai sekarang juga, supaya tidak diminta berulang-ulang
        prefs.edit().putBoolean("perm_prompted", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Izin yang dibutuhkan")
            .setMessage(
                "Supaya unduhan tetap berjalan di latar belakang dan progresnya terlihat, " +
                    "izinkan:\n\n• Notifikasi\n• Penggunaan baterai tanpa batas\n\n" +
                    "Kamu bisa mengubahnya kapan saja lewat ikon pengaturan (gerigi)."
            )
            .setCancelable(false)
            .setPositiveButton("Lanjut") { _, _ -> askNotificationThenBattery() }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun askNotificationThenBattery() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askBatteryUnrestricted()
        }
    }

    private fun askBatteryUnrestricted() {
        if (!Perms.batteryUnrestricted(this)) Perms.requestBattery(this)
    }

    // ---------- Init library ----------

    private fun initLibrary() {
        loadingActive = true
        loadingDots = 0
        loadingTick.run()
        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                ready = true
                runOnUiThread { finishLoading(".".repeat(maxDots)) }
                log("Selesai dimuat")
                log("Aplikasi siap digunakan")
                refreshButtons()
            } catch (e: Exception) {
                runOnUiThread { finishLoading(".".repeat(loadingDots)) }
                log("Gagal init: ${e.message}")
            }
        }.start()
    }

    private fun finishLoading(dots: String) {
        loadingActive = false
        uiHandler.removeCallbacks(loadingTick)
        logBuf.append(loadingText).append(dots).append("\n")
        renderLog(null)
    }

    private fun renderLog(loadingLine: String?) {
        tvLog.text = if (loadingLine != null) logBuf.toString() + loadingLine else logBuf.toString()
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun log(msg: String) = runOnUiThread {
        logBuf.append(msg).append("\n")
        renderLog(if (loadingActive) loadingText + ".".repeat(loadingDots) else null)
    }

    // ---------- DownloadState.Listener ----------

    override fun onLog(msg: String) = log(msg)

    override fun onStateChanged() {
        val s = DownloadState
        progressBar.progress = s.progress
        tvStatus.text = s.line
        btnCancel.visibility = if (s.status == DownloadState.Status.RUNNING) View.VISIBLE else View.GONE
        btnOpen.visibility =
            if (s.status == DownloadState.Status.DONE && s.lastUri != null) View.VISIBLE else View.GONE
        refreshButtons()
    }

    private fun refreshButtons() = runOnUiThread {
        val running = DownloadState.status == DownloadState.Status.RUNNING
        val enabled = ready && !busy && !running
        btnDownload.isEnabled = enabled
        btnUpdate.isEnabled = enabled
    }

    // ---------- Update yt-dlp ----------

    private fun startUpdate() {
        if (!ready || busy) return
        busy = true
        refreshButtons()
        log("Update yt-dlp...")
        Thread {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(applicationContext, UpdateChannel.STABLE)
                log("Update: $status")
            } catch (e: Exception) {
                log("Update gagal: ${e.message}")
            } finally {
                busy = false
                refreshButtons()
            }
        }.start()
    }

    // ---------- Unduh / batal ----------

    private fun startDownload() {
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Isi link dulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ready || busy || DownloadState.status == DownloadState.Status.RUNNING) return

        DownloadState.begin()
        try {
            DownloadService.start(this, url, rbAudio.isChecked)
        } catch (e: Exception) {
            DownloadState.finish(DownloadState.Status.ERROR)
            log("Gagal memulai unduhan: ${e.message}")
        }
    }

    private fun cancelDownload() {
        if (DownloadState.status != DownloadState.Status.RUNNING) return
        Toast.makeText(this, "Membatalkan unduhan...", Toast.LENGTH_SHORT).show()
        DownloadService.cancel(this)
    }

    private fun openLastFile() {
        val uri = DownloadState.lastUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DownloadState.lastMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka file ini", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Update aplikasi (via GitHub Releases, SemVer) ----------

    /** "v1.2.3" -> 10203. versionCode aplikasi memakai rumus yang sama. */
    private fun semverToCode(tag: String): Long? {
        val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)""").find(tag.trim()) ?: return null
        val (major, minor, patch) = m.destructured
        return major.toLong() * 10000 + minor.toLong() * 100 + patch.toLong()
    }

    private fun checkAppUpdate() {
        if (appUpdating) return
        if (DownloadState.status == DownloadState.Status.RUNNING) {
            Toast.makeText(this, "Tunggu unduhan selesai dulu", Toast.LENGTH_SHORT).show()
            return
        }
        appUpdating = true
        btnCheckUpdate.isEnabled = false
        log("Mengecek update aplikasi...")
        Thread {
            try {
                val conn = (URL(releaseApi).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "QuickDownloader")
                }
                if (conn.responseCode == 404) {
                    log("Belum ada rilis di GitHub.")
                    return@Thread
                }
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")

                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.getString("tag_name")
                val remote = semverToCode(tag)
                    ?: throw IOException("Format tag tidak dikenali: $tag (harus seperti v1.2.3)")
                val info = packageManager.getPackageInfo(packageName, 0)
                val local = info.longVersionCode
                val localName = info.versionName ?: "?"

                if (remote <= local) {
                    log("Sudah versi terbaru (v$localName).")
                    return@Thread
                }

                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url")
                        break
                    }
                }
                if (apkUrl == null) {
                    log("Rilis $tag tidak memiliki file APK.")
                    return@Thread
                }
                log("Versi baru tersedia: $tag (terpasang: v$localName)")
                runOnUiThread { showUpdateDialog(tag, localName, apkUrl) }
            } catch (e: Exception) {
                log("Cek update gagal: ${e.message}")
            } finally {
                appUpdating = false
                runOnUiThread { btnCheckUpdate.isEnabled = true }
            }
        }.start()
    }

    private fun showUpdateDialog(tag: String, localName: String, apkUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Update tersedia")
            .setMessage("Versi baru $tag tersedia (terpasang: v$localName). Unduh dan pasang sekarang?")
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(apkUrl) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun downloadAndInstall(apkUrl: String) {
        if (appUpdating) return
        appUpdating = true
        btnCheckUpdate.isEnabled = false
        progressBar.progress = 0
        log("Mengunduh update...")
        Thread {
            val dir = File(cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, "QuickDownloader.apk")
            try {
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "QuickDownloader")
                }
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                runOnUiThread { progressBar.progress = pct }
                            }
                        }
                    }
                }
                log("Unduhan selesai, membuka installer...")
                runOnUiThread { installApk(apk) }
            } catch (e: Exception) {
                log("Unduh update gagal: ${e.message}")
            } finally {
                appUpdating = false
                runOnUiThread { btnCheckUpdate.isEnabled = true }
            }
        }.start()
    }

    private fun installApk(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Izinkan \"Install aplikasi tidak dikenal\" untuk Quick Downloader, lalu cek update lagi",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Tidak bisa membuka installer", Toast.LENGTH_SHORT).show()
        }
    }
}
