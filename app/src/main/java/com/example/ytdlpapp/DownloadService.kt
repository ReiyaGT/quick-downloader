package com.example.ytdlpapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException

/**
 * Foreground service yang menjalankan unduhan, supaya tetap jalan di background,
 * dengan notifikasi progres (+ tombol Batalkan) dan notifikasi selesai.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.example.ytdlpapp.action.START"
        const val ACTION_CANCEL = "com.example.ytdlpapp.action.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_AUDIO = "audio_only"

        private const val CH_PROGRESS = "download_progress"
        private const val CH_DONE = "download_done"
        private const val ID_PROGRESS = 1001
        private const val ID_DONE = 1002
        private const val PROCESS_ID = "quick-downloader-job"

        fun start(ctx: Context, url: String, audioOnly: Boolean) {
            val i = Intent(ctx, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_AUDIO, audioOnly)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }

    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CANCEL -> handleCancel()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (worker?.isAlive == true) {
            cancelled = true
            destroyProcessAsync()
        }
        super.onDestroy()
    }

    // ---------- Start / Cancel ----------

    private fun handleStart(intent: Intent) {
        ensureChannels()
        // Wajib dipanggil cepat setelah startForegroundService()
        ServiceCompat.startForeground(
            this,
            ID_PROGRESS,
            progressNotification(0, "Menyiapkan..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (worker?.isAlive == true) return

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO, false)

        cancelled = false
        lastNotifyMs = 0L
        DownloadState.begin()
        acquireWakeLock()
        worker = Thread { runDownload(url, audioOnly) }.also { it.start() }
    }

    private fun handleCancel() {
        if (worker?.isAlive != true) {
            stopSelf()
            return
        }
        cancelled = true
        DownloadState.log("Membatalkan unduhan...")
        notificationManager().notify(ID_PROGRESS, progressNotification(-1, "Membatalkan..."))
        destroyProcessAsync()
    }

    private fun destroyProcessAsync() {
        Thread {
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
            } catch (_: Exception) {
            }
        }.start()
    }

    // ---------- Proses unduhan ----------

    private fun runDownload(url: String, audioOnly: Boolean) {
        val tmp = File(cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
        var result = DownloadState.Status.ERROR
        var savedUri: Uri? = null
        var savedMime = "*/*"
        var savedName = ""

        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
            if (cancelled) throw IOException("dibatalkan")

            DownloadState.log("Mulai: $url")
            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--no-mtime")
            request.addOption("-o", "${tmp.absolutePath}/%(title).100s.%(ext)s")
            if (audioOnly) {
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
            } else {
                request.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b")
                request.addOption("--merge-output-format", "mp4")
            }

            YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, line ->
                if (cancelled) {
                    // Jaga-jaga kalau cancel datang sebelum proses terdaftar
                    try {
                        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
                    } catch (_: Exception) {
                    }
                } else {
                    val pct = progress.toInt().coerceIn(0, 100)
                    DownloadState.update(pct, line)
                    notifyProgress(pct, line)
                }
            }

            if (cancelled) {
                result = DownloadState.Status.CANCELLED
            } else {
                val files = tmp.listFiles()
                    ?.filter { it.isFile && it.extension !in listOf("part", "ytdl", "temp") }
                    .orEmpty()
                if (files.isEmpty()) {
                    DownloadState.log("Tidak ada file hasil.")
                } else {
                    val sub = if (audioOnly) "Audio" else "Video"
                    files.forEach {
                        val (uri, mime) = saveToDownloads(it, sub)
                        savedUri = uri
                        savedMime = mime
                        savedName = it.name
                        DownloadState.log("Tersimpan: Download/QuickDownloader/$sub/${it.name}")
                    }
                    result = DownloadState.Status.DONE
                }
            }
        } catch (e: Exception) {
            if (cancelled) {
                result = DownloadState.Status.CANCELLED
            } else {
                DownloadState.log("Error: ${e.message}")
            }
        } finally {
            tmp.deleteRecursively()
        }

        finishWork(result, savedUri, savedMime, savedName)
    }

    private fun finishWork(result: DownloadState.Status, uri: Uri?, mime: String, name: String) {
        releaseWakeLock()
        DownloadState.finish(result, uri, mime, name)
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = notificationManager()
        nm.cancel(ID_PROGRESS)

        when (result) {
            DownloadState.Status.DONE -> if (uri != null) nm.notify(ID_DONE, doneNotification(uri, mime, name))
            DownloadState.Status.CANCELLED -> DownloadState.log("Unduhan dibatalkan.")
            else -> nm.notify(ID_DONE, errorNotification())
        }
        stopSelf()
    }

    // ---------- Penyimpanan ----------

    private fun saveToDownloads(file: File, subFolder: String): Pair<Uri, String> {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/QuickDownloader/$subFolder"
            )
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gagal membuat file di Downloads")
        contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Gagal menulis file")
        return uri to mime
    }

    // ---------- Notifikasi ----------

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun ensureChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Progres unduhan", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Unduhan selesai", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** pct < 0 atau 0 = progres tidak pasti (indeterminate). */
    private fun progressNotification(pct: Int, text: String): Notification {
        val title = if (pct > 0) "Mengunduh... $pct%" else "Mengunduh..."
        return NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(200)))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, pct.coerceAtLeast(0), pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent())
            .addAction(0, "Batalkan", cancelPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notifyProgress(pct: Int, line: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyMs < 500 && pct < 100) return
        lastNotifyMs = now
        notificationManager().notify(ID_PROGRESS, progressNotification(pct, line))
    }

    private fun doneNotification(uri: Uri, mime: String, name: String): Notification {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 2, view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Unduhan selesai")
            .setContentText(if (name.isNotEmpty()) name else "Ketuk untuk membuka file")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
    }

    private fun errorNotification(): Notification =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Unduhan gagal")
            .setContentText("Ketuk untuk melihat detail di aplikasi")
            .setContentIntent(mainPendingIntent())
            .setAutoCancel(true)
            .build()

    // ---------- Wake lock ----------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuickDownloader:download").apply {
            acquire(3 * 60 * 60 * 1000L) // batas aman 3 jam
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}
