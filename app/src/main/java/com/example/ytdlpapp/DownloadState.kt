package com.example.ytdlpapp

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Status unduhan yang dibagi antara DownloadService dan MainActivity.
 * Service menulis status; Activity mendengarkan lewat Listener saat terlihat.
 */
object DownloadState {

    enum class Status { IDLE, RUNNING, DONE, ERROR, CANCELLED }

    interface Listener {
        fun onStateChanged()
        fun onLog(msg: String)
    }

    @Volatile
    var status: Status = Status.IDLE
        private set

    @Volatile
    var progress: Int = 0
        private set

    @Volatile
    var line: String = ""
        private set

    @Volatile
    var lastUri: Uri? = null
        private set

    @Volatile
    var lastMime: String = "*/*"
        private set

    @Volatile
    var lastName: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val pendingLogs = ConcurrentLinkedQueue<String>()
    private var listener: Listener? = null // hanya diakses dari main thread

    fun attach(l: Listener) {
        listener = l
        flushLogs()
        l.onStateChanged()
    }

    fun detach(l: Listener) {
        if (listener === l) listener = null
    }

    fun begin() {
        status = Status.RUNNING
        progress = 0
        line = ""
        lastUri = null
        lastName = ""
        changed()
    }

    fun update(pct: Int, text: String) {
        progress = pct
        line = text
        changed()
    }

    fun finish(result: Status, uri: Uri? = null, mime: String = "*/*", name: String = "") {
        status = result
        if (result == Status.DONE) {
            lastUri = uri
            lastMime = mime
            lastName = name
        } else {
            progress = 0
            line = ""
        }
        changed()
    }

    fun log(msg: String) {
        pendingLogs.add(msg)
        main.post { flushLogs() }
    }

    private fun changed() {
        main.post { listener?.onStateChanged() }
    }

    private fun flushLogs() {
        val l = listener ?: return
        while (true) {
            val m = pendingLogs.poll() ?: break
            l.onLog(m)
        }
    }
}
