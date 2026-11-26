package net.ssync.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.ssync.app.MainActivity
import net.ssync.player.DownloadQueue
import net.ssync.player.DownloadTask
import net.ssync.player.FitVerdict
import net.ssync.player.MusicApiResult
import net.ssync.player.OfflineApiClient
import net.ssync.player.OfflineFormat
import net.ssync.player.OfflineStorage
import net.ssync.player.TaskState
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * runs the download queue in a foreground service, so it keeps going with the
 * app closed or the screen off. one file downloads at a time while the server
 * converts the next ones; a dropped connection resumes with a Range request.
 */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "soulsync_downloads"
        private const val NOTIFY_ID = 4107
        private const val DONE_ID = 4108
        const val ACTION_PAUSE_ALL = "net.ssync.app.downloads.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "net.ssync.app.downloads.RESUME_ALL"

        /** start (or poke) the engine when there's something to do; safe to call often */
        fun start(context: Context) {
            OfflineDownloads.init(context)
            if (DownloadQueue.runnable(OfflineDownloads.tasks.value).isEmpty()) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }

        val running = MutableStateFlow(false)
        /** "Waiting for Wi-Fi" and the like, for the Downloads screen */
        val waitingReason = MutableStateFlow<String?>(null)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var finishedCount = 0
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OfflineDownloads.init(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> OfflineDownloads.pauseAll()
            ACTION_RESUME_ALL -> OfflineDownloads.resumeAll(this)
        }
        if (!inForeground && !goForeground(buildNotification("Getting downloads ready", null, null))) {
            // android won't let it start from the background now; the app starts it next time it opens
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                running.value = true
                try {
                    // something queued while the last pass was ending still gets its turn
                    do {
                        Engine(this@DownloadService).run()
                    } while (coroutineContext.isActive && hasWork())
                } finally {
                    running.value = false
                    waitingReason.value = null
                    finish()
                }
            }
        }
        return START_STICKY
    }

    // android 15 caps data-sync services at 6 hours a day: stop cleanly, resume next time
    override fun onTimeout(startId: Int, fgsType: Int) {
        loop?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        running.value = false
        super.onDestroy()
    }

    private fun hasWork(): Boolean =
        DownloadQueue.runnable(OfflineDownloads.tasks.value).isNotEmpty() && waitingReason.value == null

    private fun goForeground(n: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, n)
        }
        inForeground = true
        true
    } catch (e: Exception) {
        false
    }

    private fun finish() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tasks = OfflineDownloads.tasks.value
        val failed = tasks.count { it.state == TaskState.FAILED }
        val paused = tasks.count { it.state == TaskState.PAUSED }
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        if (finishedCount > 0 || failed > 0) {
            val title = when {
                finishedCount > 0 && failed == 0 -> if (finishedCount == 1) "Download finished" else "$finishedCount downloads finished"
                finishedCount > 0 -> "$finishedCount downloaded, $failed didn't"
                else -> if (failed == 1) "A download didn't finish" else "$failed downloads didn't finish"
            }
            val text = when {
                failed > 0 -> "Open Downloads to try again."
                paused > 0 -> "$paused paused. Ready to watch offline."
                else -> "Ready to play with no connection."
            }
            runCatching {
                nm.notify(
                    DONE_ID,
                    NotificationCompat.Builder(this, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(openApp())
                        .setAutoCancel(true)
                        .build(),
                )
            }
        }
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of downloads for offline play"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(act: String): PendingIntent = PendingIntent.getService(
        this, act.hashCode(),
        Intent(this, DownloadService::class.java).setAction(act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun buildNotification(title: String, text: String?, progress: Double?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Pause all", action(ACTION_PAUSE_ALL))
        if (text != null) b.setContentText(text)
        when {
            progress == null -> b.setProgress(0, 0, true)
            progress >= 0 -> b.setProgress(1000, (progress * 1000).toInt(), false)
        }
        return b.build()
    }

    internal fun notifyProgress(title: String, text: String?, progress: Double?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFY_ID, buildNotification(title, text, progress)) }
    }

    internal fun noteFinished() {
        finishedCount++
    }

    /** the queue runner */
    private class Engine(private val service: DownloadService) {
        private val ctx: Context = service.applicationContext
        private val notBefore = HashMap<String, Long>()
        private val lastPoll = HashMap<String, Long>()
        private var lastNotify = 0L
        @Volatile private var currentCall: okhttp3.Call? = null
        private var lastApi: OfflineApiClient? = null
        private var lastReachable = 0L

        suspend fun run() {
            var unreachableSince = 0L
            while (coroutineContext.isActive) {
                val runnable = DownloadQueue.runnable(OfflineDownloads.tasks.value)
                if (runnable.isEmpty()) return

                if (!networkAllowed()) {
                    val reason = if (OfflineDownloads.settings.value.wifiOnly) "Waiting for Wi-Fi" else "Waiting for a connection"
                    waitingReason.value = reason
                    service.notifyProgress(reason, "${runnable.size} waiting · they start on their own", -1.0)
                    // waits up to half an hour, then lets the phone rest; opening the app starts it again
                    val came = withTimeoutOrNull(30 * 60_000L) { waitForNetwork() }
                    if (came == null) return
                    continue
                }
                waitingReason.value = null

                val api = OfflineSync.client(ctx)
                val fresh = System.currentTimeMillis() - lastReachable < 30_000L
                if (api == null || (!fresh && !api.reachable())) {
                    if (unreachableSince == 0L) unreachableSince = System.currentTimeMillis()
                    waitingReason.value = "Can't reach your server"
                    service.notifyProgress("Can't reach your server", "Trying again shortly", -1.0)
                    if (System.currentTimeMillis() - unreachableSince > 20 * 60_000L) return
                    delay(30_000L)
                    continue
                }
                unreachableSince = 0L
                if (!fresh) lastReachable = System.currentTimeMillis()

                val now = System.currentTimeMillis()
                val due = runnable.filter { (notBefore[it.key] ?: 0L) <= now }

                // ask the server for the next few so their conversions run while one downloads
                for (t in due.filter { it.prepareId == null }.take(3)) prepare(api, t)
                for (t in currentTasks(due).filter { it.state == TaskState.PREPARING }) {
                    if (now - (lastPoll[t.key] ?: 0L) >= 3_000L) poll(api, t)
                }

                val ready = currentTasks(due).firstOrNull { it.state == TaskState.QUEUED && it.fileUrl != null }
                if (ready == null) {
                    val converting = currentTasks(due).firstOrNull { it.state == TaskState.PREPARING }
                    if (converting != null) {
                        notifyMaybe(
                            "Server is preparing ${converting.title}",
                            "${(converting.convertProgress * 100).toInt()}% converted · ${runnable.size} in the queue",
                            converting.convertProgress,
                            force = true,
                        )
                    }
                    val wait = runnable.mapNotNull { notBefore[it.key] }.filter { it > now }.minOrNull()?.minus(now)
                    delay((wait ?: 3_000L).coerceIn(1_000L, 3_000L))
                    continue
                }

                val fit = OfflineStorage.canStart(
                    ready, OfflineDownloads.tasks.value,
                    OfflineDownloads.settings.value.limitBytes, OfflineDownloads.freeBytes(),
                )
                if (!fit.fits) {
                    OfflineDownloads.updateTask(ready.key) {
                        it.copy(
                            state = TaskState.FAILED,
                            error = if (fit.verdict == FitVerdict.OVER_LIMIT) "Over your storage limit" else "Not enough space on this phone",
                        )
                    }
                    OfflineSync.requestReport()
                    continue
                }
                lastApi = api
                download(api, ready)
            }
        }

        private fun currentTasks(keys: List<DownloadTask>): List<DownloadTask> {
            val want = keys.map { it.key }.toSet()
            return DownloadQueue.runnable(OfflineDownloads.tasks.value).filter { it.key in want }
        }

        private suspend fun prepare(api: OfflineApiClient, t: DownloadTask) {
            when (val res = api.prepare(t.itemType, t.itemId, t.quality)) {
                is MusicApiResult.Success -> applyPrepare(api, t.key, res.data.id, res.data)
                is MusicApiResult.Error -> backoff(t, res.message)
                MusicApiResult.DeviceRevoked -> fail(t.key, "This phone was signed out of SoulSync")
            }
        }

        private suspend fun poll(api: OfflineApiClient, t: DownloadTask) {
            val id = t.prepareId ?: return
            lastPoll[t.key] = System.currentTimeMillis()
            when (val res = api.pollPrepare(id)) {
                is MusicApiResult.Success -> applyPrepare(api, t.key, id, res.data)
                is MusicApiResult.Error -> backoff(t, res.message)
                MusicApiResult.DeviceRevoked -> fail(t.key, "This phone was signed out of SoulSync")
            }
        }

        private fun applyPrepare(api: OfflineApiClient, key: String, id: String, p: net.ssync.player.OfflinePrepare) {
            OfflineDownloads.updateTask(key) { t ->
                if (!t.state.isActive) return@updateTask t
                when (p.status) {
                    "ready" -> t.copy(
                        state = TaskState.QUEUED,
                        prepareId = p.id.ifBlank { id },
                        fileUrl = api.absolute(p.url?.takeIf { it.isNotBlank() } ?: api.fileUrl(p.id.ifBlank { id })),
                        totalBytes = if (p.sizeBytes > 0) p.sizeBytes else t.totalBytes,
                        convertProgress = 1.0,
                        error = null,
                    )
                    "queued", "converting" -> t.copy(
                        state = TaskState.PREPARING,
                        prepareId = p.id.ifBlank { id },
                        convertProgress = p.progress.coerceIn(0.0, 1.0),
                        error = null,
                    )
                    else -> t.copy(
                        state = TaskState.FAILED,
                        prepareId = null,
                        fileUrl = null,
                        error = p.problem ?: "The server couldn't prepare this",
                    )
                }
            }
        }

        private fun backoff(t: DownloadTask, message: String) {
            val attempts = t.attempts + 1
            if (attempts >= DownloadQueue.MAX_AUTO_ATTEMPTS) {
                fail(t.key, message)
                return
            }
            notBefore[t.key] = System.currentTimeMillis() + DownloadQueue.retryDelayMs(attempts)
            OfflineDownloads.updateTask(t.key) { it.copy(attempts = attempts, error = "Retrying: $message") }
        }

        private fun fail(key: String, message: String) {
            OfflineDownloads.updateTask(key) { it.copy(state = TaskState.FAILED, error = message) }
            OfflineSync.requestReport()
        }

        /** download one file, stopping the moment it's paused, cancelled or the network turns metered */
        private suspend fun download(api: OfflineApiClient, start: DownloadTask) = coroutineScope {
            val key = start.key
            OfflineDownloads.updateTask(key) { it.copy(state = TaskState.DOWNLOADING, error = null) }
            OfflineSync.requestReport()
            val worker = launch(Dispatchers.IO) { transfer(api, start) }
            val watcher = launch {
                while (isActive && worker.isActive) {
                    val t = OfflineDownloads.tasks.value.firstOrNull { it.key == key }
                    if (t == null || t.state != TaskState.DOWNLOADING || !networkAllowed()) {
                        worker.cancel()
                        // a read waiting on a slow server stops now, not at its timeout
                        currentCall?.cancel()
                        break
                    }
                    delay(400L)
                }
            }
            worker.join()
            watcher.cancel()
            OfflineDownloads.setLive(key, null)
            // stopped by the network rule, not by a person: back in the queue
            OfflineDownloads.updateTask(key) { if (it.state == TaskState.DOWNLOADING) it.copy(state = TaskState.QUEUED) else it }
        }

        private suspend fun transfer(api: OfflineApiClient, t: DownloadTask) {
            val part = OfflineDownloads.partFile(t) ?: return
            val url = t.fileUrl ?: return
            var have = if (part.exists()) part.length() else 0L
            val req = Request.Builder().url(url).get()
            if (have > 0) {
                req.header("Range", "bytes=$have-")
                t.etag?.let { req.header("If-Range", it) }
            }
            val call = api.http.newCall(req.build())
            currentCall = call
            try {
                call.execute().use { resp ->
                    when {
                        resp.code == 416 -> {
                            // asked past the end: finished already if the size agrees, else start over
                            if (t.totalBytes > 0 && have >= t.totalBytes) {
                                complete(t, part, resp.header("Content-Disposition"), resp.header("Content-Type"))
                            } else {
                                part.delete()
                                OfflineDownloads.updateTask(t.key) { it.copy(bytesDone = 0, etag = null) }
                            }
                            return
                        }
                        resp.code == 404 || resp.code == 410 -> {
                            // the server let its copy go: prepare it again
                            OfflineDownloads.updateTask(t.key) {
                                it.copy(state = TaskState.QUEUED, prepareId = null, fileUrl = null, convertProgress = 0.0)
                            }
                            return
                        }
                        !resp.isSuccessful -> {
                            val msg = OfflineApiClient.serverMessage(resp.code, resp.body?.string().orEmpty())
                            if (OfflineApiClient.isRetryable(resp.code)) backoff(t, msg) else fail(t.key, msg)
                            return
                        }
                    }
                    val body = resp.body ?: throw IOException("Empty response")
                    var total: Long
                    if (resp.code == 206) {
                        // "bytes 1000-4999/5000": it must pick up where the file stops
                        val range = resp.header("Content-Range").orEmpty()
                        val from = range.substringAfter("bytes ").substringBefore('-').toLongOrNull()
                        total = range.substringAfter('/').toLongOrNull() ?: (have + body.contentLength())
                        if (from != null && from != have) {
                            part.delete()
                            OfflineDownloads.updateTask(t.key) { it.copy(bytesDone = 0) }
                            throw IOException("The server resumed at the wrong place")
                        }
                    } else {
                        // a whole file: the server can't resume or the file changed
                        have = 0L
                        total = body.contentLength()
                    }
                    val etag = resp.header("ETag")
                    OfflineDownloads.updateTask(t.key) {
                        it.copy(totalBytes = if (total > 0) total else it.totalBytes, etag = etag ?: it.etag, bytesDone = have)
                    }
                    var done = have
                    var lastSave = System.currentTimeMillis()
                    var windowStart = lastSave
                    var windowBytes = 0L
                    var speed = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(part, have > 0).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (!coroutineContext.isActive) throw CancellationException("stopped")
                                val n = input.read(buf)
                                if (n == -1) break
                                out.write(buf, 0, n)
                                done += n
                                windowBytes += n
                                val now = System.currentTimeMillis()
                                if (now - windowStart >= 1_000L) {
                                    val inst = windowBytes * 1000 / (now - windowStart)
                                    speed = if (speed == 0L) inst else (speed * 6 + inst * 4) / 10
                                    windowStart = now
                                    windowBytes = 0
                                    OfflineDownloads.setLive(t.key, LiveProgress(done, total, speed))
                                    val frac = if (total > 0) done.toDouble() / total else -1.0
                                    notifyMaybe(
                                        t.title,
                                        listOfNotNull(
                                            if (total > 0) "${OfflineFormat.bytes(done)} of ${OfflineFormat.bytes(total)}" else OfflineFormat.bytes(done),
                                            OfflineFormat.speed(speed).takeIf { it.isNotBlank() },
                                            queueLine(),
                                        ).joinToString(" · "),
                                        frac,
                                    )
                                }
                                if (now - lastSave >= 3_000L) {
                                    lastSave = now
                                    val saved = done
                                    OfflineDownloads.updateTask(t.key) { it.copy(bytesDone = saved) }
                                }
                            }
                        }
                    }
                    OfflineDownloads.updateTask(t.key) { it.copy(bytesDone = done) }
                    if (total > 0 && done < total) throw IOException("The connection dropped")
                    complete(t.copy(totalBytes = total), part, resp.header("Content-Disposition"), resp.header("Content-Type"))
                }
            } catch (e: CancellationException) {
                val size = if (part.exists()) part.length() else 0L
                OfflineDownloads.updateTask(t.key) { it.copy(bytesDone = size) }
                throw e
            } catch (e: Exception) {
                val size = if (part.exists()) part.length() else 0L
                if (!coroutineContext.isActive || call.isCanceled()) {
                    // paused, cancelled or off wi-fi: keep the bytes, leave the state to whoever stopped it
                    OfflineDownloads.updateTask(t.key) { it.copy(bytesDone = size) }
                    return
                }
                OfflineDownloads.updateTask(t.key) {
                    it.copy(
                        bytesDone = size,
                        state = if (it.state == TaskState.DOWNLOADING) TaskState.QUEUED else it.state,
                        // a drop after real progress starts the count of tries again
                        attempts = if (size > t.bytesDone) 0 else it.attempts,
                    )
                }
                // a byte-level drop keeps its place and resumes; only repeated ones give up
                backoff(OfflineDownloads.tasks.value.firstOrNull { it.key == t.key } ?: t, e.message ?: "The connection dropped")
            } finally {
                currentCall = null
            }
        }

        private fun complete(t: DownloadTask, part: File, disposition: String?, contentType: String?) {
            val ext = extensionFor(t, disposition, contentType)
            val dest = File(part.parentFile, OfflineDownloads.safeName(t) + "." + ext)
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            val size = dest.length()
            val art = saveArtwork(t, dest.parentFile)
            OfflineDownloads.updateTask(t.key) {
                it.copy(
                    state = TaskState.DOWNLOADED,
                    localPath = dest.absolutePath,
                    bytesDone = size,
                    totalBytes = size,
                    completedAtMs = System.currentTimeMillis(),
                    localArtwork = art ?: it.localArtwork,
                    error = null,
                    attempts = 0,
                )
            }
            // a smaller copy replaced an older one of the same thing: the old file goes
            OfflineDownloads.tasks.value.firstOrNull { it.key == t.key }?.let { now ->
                if (t.localPath.isNotBlank() && t.localPath != now.localPath) runCatching { File(t.localPath).delete() }
            }
            notBefore.remove(t.key)
            service.noteFinished()
            OfflineSync.requestReport()
        }

        /** the card's artwork kept beside the file (server paths only: the pinned client can't fetch elsewhere) */
        private fun saveArtwork(t: DownloadTask, dir: File?): String? {
            val path = (t.groupArtwork ?: t.artwork)?.takeIf { it.startsWith("/api/") } ?: return null
            val api = lastApi ?: return null
            val out = File(dir ?: return null, OfflineDownloads.safeName(t) + ".art")
            return runCatching {
                val sep = if ('?' in path) "&" else "?"
                api.http.newCall(Request.Builder().url(api.absolute(path + sep + "w=342")).get().build()).execute().use { r ->
                    if (!r.isSuccessful) return null
                    val bytes = r.body?.bytes() ?: return null
                    if (bytes.isEmpty()) return null
                    out.writeBytes(bytes)
                }
                out.absolutePath
            }.getOrNull()
        }

        private fun extensionFor(t: DownloadTask, disposition: String?, contentType: String?): String {
            val named = disposition?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }
                ?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
            if (named != null) return named
            return when {
                contentType?.contains("mp4") == true -> if (t.isVideo) "mp4" else "m4a"
                contentType?.contains("matroska") == true -> "mkv"
                contentType?.contains("mpeg") == true && !t.isVideo -> "mp3"
                contentType?.contains("flac") == true -> "flac"
                contentType?.contains("ogg") == true -> "ogg"
                t.isVideo -> "mp4"
                else -> "m4a"
            }
        }

        private fun queueLine(): String? {
            val n = DownloadQueue.runnable(OfflineDownloads.tasks.value).size
            return if (n > 1) "${n - 1} more after this" else null
        }

        private fun notifyMaybe(title: String, text: String?, progress: Double?, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastNotify < 1_000L) return
            if (force && now - lastNotify < 2_500L) return
            lastNotify = now
            service.notifyProgress(title, text, progress)
        }

        private fun networkAllowed(): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            if (OfflineDownloads.settings.value.wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
            return true
        }

        /** returns once the network rule is met (or a setting changes to allow it) */
        private suspend fun waitForNetwork() {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val changed = MutableStateFlow(0)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { changed.value++ }
                override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { changed.value++ }
            }
            runCatching { cm?.registerDefaultNetworkCallback(cb) }
            try {
                while (!networkAllowed()) {
                    if (DownloadQueue.runnable(OfflineDownloads.tasks.value).isEmpty()) return
                    val seen = changed.value
                    // a settings change (wi-fi only off) doesn't ring the callback: look every few seconds too
                    withTimeoutOrNull(5_000L) { changed.first { it != seen } }
                }
            } finally {
                runCatching { cm?.unregisterNetworkCallback(cb) }
            }
        }
    }
}
