package net.ssync.app.player

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.ssync.app.MainActivity
import net.ssync.app.R
import net.ssync.network.CandidateAddressRacer
import net.ssync.network.CertPinningOkHttp
import net.ssync.pairing.ui.isAndroidTv
import net.ssync.player.MusicApiResult
import net.ssync.player.NotificationChannels
import net.ssync.player.NotificationCursor
import net.ssync.player.NotificationGrouping
import net.ssync.player.NotificationKindInfo
import net.ssync.player.NotificationsApiClient
import net.ssync.player.NotificationsJson
import net.ssync.player.PlayerArtwork
import net.ssync.player.PlayerNotification
import net.ssync.storage.KeystoreServerVault
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * the app's side of player notifications: the "since" cursor, the bell's
 * unread count, posting to the system tray (phones only), the 15 minute
 * background check, and taps coming back in.
 *
 * while the app is on screen nothing goes to the tray: the bell shows it.
 * the first check on a device only sets the cursor, so the backlog never
 * floods the tray
 */
object NotificationCenter {
    private const val PREFS = "soulsync_notifications"
    private const val KEY_DEVICE_ON = "device_on"
    private const val KEY_ASKED = "asked_permission"
    private const val KEY_KINDS = "kinds_json"
    private const val JOB_ID = 0x55_4E_01

    const val ACTION_OPEN = "net.ssync.app.OPEN_NOTIFICATION"
    const val EXTRA_IDS = "ssn_ids"
    const val EXTRA_LINK = "ssn_link"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _unread = MutableStateFlow(0)
    /** the bell's count */
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** bumped whenever the list may have changed, so an open list reloads */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed.asStateFlow()

    /** a tapped notification waiting for the shell */
    data class Tap(val ids: List<Long>, val link: String?)

    private val _pendingTap = MutableStateFlow<Tap?>(null)
    val pendingTap: StateFlow<Tap?> = _pendingTap.asStateFlow()

    /** screens of the app now started; while above zero nothing goes to the tray */
    @Volatile var visibleScreens: Int = 0

    // ── this device's own settings ──

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_DEVICE_ON, true)

    fun setDeviceEnabled(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_DEVICE_ON, on).apply()
        if (on) schedule(context) else {
            cancelSchedule(context)
            runCatching { NotificationManagerCompat.from(context).cancelAll() }
        }
    }

    /** the permission was asked for once from the notifications settings */
    fun askedPermission(context: Context): Boolean = sp(context).getBoolean(KEY_ASKED, false)

    fun markAskedPermission(context: Context) {
        sp(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun systemAllows(context: Context): Boolean =
        hasPermission(context) && runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    private fun cursorKey(lookupId: String, profileId: String) = "since:$lookupId:$profileId"

    private fun readCursor(context: Context, key: String): Long? =
        sp(context).getLong(key, -1L).takeIf { it > 0L }

    private fun writeCursor(context: Context, key: String, value: Long) {
        sp(context).edit().putLong(key, value).apply()
    }

    /** the server's kinds as last seen: channel names when the app is closed */
    fun cachedKinds(context: Context): List<NotificationKindInfo> {
        val raw = sp(context).getString(KEY_KINDS, null) ?: return NotificationChannels.KNOWN
        return runCatching {
            NotificationsJson.parseKinds(kotlinx.serialization.json.Json.parseToJsonElement(raw))
        }.getOrNull()?.ifEmpty { null } ?: NotificationChannels.KNOWN
    }

    fun rememberKinds(context: Context, kinds: List<NotificationKindInfo>) {
        if (kinds.isEmpty()) return
        val arr = kotlinx.serialization.json.JsonArray(
            kinds.map { k ->
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(k.id))
                    put("label", kotlinx.serialization.json.JsonPrimitive(k.label))
                    put("help", kotlinx.serialization.json.JsonPrimitive(k.help))
                    put("default", kotlinx.serialization.json.JsonPrimitive(k.default))
                    put("locked", kotlinx.serialization.json.JsonPrimitive(k.locked))
                }
            },
        )
        sp(context).edit().putString(KEY_KINDS, arr.toString()).apply()
        ensureChannels(context, kinds)
    }

    // ── the check ──

    /**
     * fetch what's new since the cursor, move it, update the bell, and (when
     * [mayPost], the device is on and the app isn't on screen) post to the tray
     */
    suspend fun check(
        context: Context,
        client: NotificationsApiClient,
        lookupId: String,
        profileId: String,
        mayPost: Boolean,
        /** "send a test": to the tray even with the app open */
        forceTray: Boolean = false,
    ): Boolean = lock.withLock {
        val app = context.applicationContext
        val key = cursorKey(lookupId, profileId)
        val stored = readCursor(app, key)
        val res = client.fetch(since = stored, limit = 50)
        val feed = (res as? MusicApiResult.Success)?.data ?: return@withLock false
        val plan = NotificationCursor.plan(stored, feed)
        writeCursor(app, key, plan.nextCursor)
        if (_unread.value != feed.unread) _unread.value = feed.unread
        if (plan.toPost.isNotEmpty() || stored == null) _changed.value += 1
        val canPost = mayPost && (forceTray || visibleScreens == 0) && deviceEnabled(app) && !app.isAndroidTv() && systemAllows(app)
        // quiet hours and the digest are the server's: it holds those back and lets them out later
        if (canPost && plan.toPost.isNotEmpty()) post(app, client, plan.toPost)
        true
    }

    /** the shell's check: on open, on resume, and every few minutes while on screen */
    fun checkNow(context: Context, client: NotificationsApiClient, lookupId: String, profileId: String): Job =
        scope.launch { check(context, client, lookupId, profileId, mayPost = false) }

    /** the list or the shell read some: the count and the tray follow */
    fun onRead(context: Context, ids: List<Long>, unreadNow: Int? = null) {
        if (unreadNow != null) _unread.value = unreadNow.coerceAtLeast(0)
        else _unread.value = (_unread.value - ids.size).coerceAtLeast(0)
        val nm = NotificationManagerCompat.from(context)
        ids.forEach { runCatching { nm.cancel(NotificationChannels.systemId(it)) } }
        _changed.value += 1
    }

    fun onAllRead(context: Context) {
        _unread.value = 0
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
        _changed.value += 1
    }

    fun setUnread(n: Int) {
        _unread.value = n.coerceAtLeast(0)
    }

    // ── taps ──

    fun takeIntent(intent: Intent?): Boolean {
        if (intent?.action != ACTION_OPEN) return false
        val ids = intent.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
        _pendingTap.value = Tap(ids, intent.getStringExtra(EXTRA_LINK))
        return true
    }

    fun consumeTap(): Tap? = _pendingTap.value.also { _pendingTap.value = null }

    private fun tapIntent(context: Context, requestCode: Int, ids: List<Long>, link: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_IDS, ids.toLongArray())
            if (link != null) putExtra(EXTRA_LINK, link)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ── the tray ──

    private fun importanceFor(kind: String): Int = when (kind) {
        "admin_message", "party_invite" -> NotificationManager.IMPORTANCE_HIGH
        "leaving_soon", "home_memories", "new_music", "digest", "test" -> NotificationManager.IMPORTANCE_LOW
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }

    /** one channel per kind, so the system settings can tune each */
    fun ensureChannels(context: Context, kinds: List<NotificationKindInfo> = cachedKinds(context)) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannelGroup(NotificationChannelGroup("ssn_group", "SoulSync"))
            val all = (kinds + NotificationChannels.KNOWN + NotificationChannels.SYSTEM).distinctBy { it.id }
            all.forEach { k ->
                // createNotificationChannel keeps the user's settings and only renames
                nm.createNotificationChannel(
                    NotificationChannel(NotificationChannels.channelId(k.id), k.label, importanceFor(k.id)).apply {
                        if (k.help.isNotBlank()) description = k.help
                        group = "ssn_group"
                    },
                )
            }
        }
    }

    private suspend fun post(
        context: Context,
        client: NotificationsApiClient,
        items: List<PlayerNotification>,
    ) {
        val kinds = cachedKinds(context)
        ensureChannels(context, kinds)
        val nm = NotificationManagerCompat.from(context)
        val art = ArtFetcher(client)
        for (group in NotificationGrouping.plan(items)) {
            val channel = NotificationChannels.channelId(group.kind)
            val groupKey = NotificationChannels.groupKey(group.kind)
            val label = kinds.firstOrNull { it.id == group.kind }?.label ?: NotificationChannels.fallbackLabel(group.kind)
            // oldest first, so the newest lands on top
            for (n in group.shown.reversed()) {
                val b = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_stat_soulsync)
                    .setContentTitle(n.title.ifBlank { label })
                    .setContentText(n.body.ifBlank { null })
                    .setStyle(NotificationCompat.BigTextStyle().bigText(n.body.ifBlank { n.title }))
                    .setAutoCancel(true)
                    .setGroup(groupKey)
                    .setOnlyAlertOnce(true)
                    .setCategory(if (group.kind == "admin_message") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setContentIntent(tapIntent(context, NotificationChannels.systemId(n.id), listOf(n.id), n.link))
                    // a group alerts once, from its summary
                    .setGroupAlertBehavior(if (group.needsSummary) NotificationCompat.GROUP_ALERT_SUMMARY else NotificationCompat.GROUP_ALERT_ALL)
                art.bitmap(n.image)?.let { b.setLargeIcon(it) }
                runCatching { nm.notify(NotificationChannels.systemId(n.id), b.build()) }
            }
            if (group.needsSummary) {
                val inbox = NotificationCompat.InboxStyle().setSummaryText(label)
                group.shown.forEach { inbox.addLine(it.title) }
                val more = group.total - group.shown.size
                if (more > 0) inbox.addLine("and $more more")
                val summary = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_stat_soulsync)
                    .setContentTitle(label)
                    .setContentText("${group.total} new")
                    .setStyle(inbox)
                    .setGroup(groupKey)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(tapIntent(context, NotificationChannels.summaryId(group.kind), group.shown.map { it.id }, null))
                    .build()
                runCatching { nm.notify(NotificationChannels.summaryId(group.kind), summary) }
            }
        }
    }

    /** artwork for the tray: the server's over the pinned client, others over plain https */
    private class ArtFetcher(client: NotificationsApiClient) {
        private val pinned = client.http
        private val base = client.base
        private val artwork = PlayerArtwork(base)
        private val external by lazy {
            OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        }

        suspend fun bitmap(image: String?): Bitmap? {
            val url = artwork.resolve(image) ?: return null
            if (!url.startsWith("https://") && !url.startsWith(base)) return null
            val http = if (url.startsWith(base)) pinned else external
            return withContext(Dispatchers.IO) {
                withTimeoutOrNull(12_000L) {
                    runCatching {
                        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                            if (!r.isSuccessful) return@use null
                            val bytes = r.body?.bytes() ?: return@use null
                            if (bytes.size > 6 * 1024 * 1024) return@use null
                            decodeScaled(bytes, 256)
                        }
                    }.getOrNull()
                }
            }
        }

        private fun decodeScaled(bytes: ByteArray, target: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    // ── the background check ──

    fun schedule(context: Context) {
        val app = context.applicationContext
        if (app.isAndroidTv() || !deviceEnabled(app)) return
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, NotificationCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.MINUTES.toMillis(15), TimeUnit.MINUTES.toMillis(5))
            // survives a reboot (RECEIVE_BOOT_COMPLETED)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(info) }
    }

    fun cancelSchedule(context: Context) {
        runCatching { context.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
    }

    /** one background run: built from the paired record, as the app may be closed */
    internal suspend fun backgroundCheck(context: Context): Boolean {
        val app = context.applicationContext
        if (app.isAndroidTv() || !deviceEnabled(app)) return true
        val record = runCatching { KeystoreServerVault(app).load() }.getOrNull() ?: run {
            cancelSchedule(app)
            return true
        }
        val http = runCatching {
            CertPinningOkHttp.createPinnedClient(
                expectedCertFingerprintSha256 = record.certFingerprintSha256,
                deviceTokenProvider = { record.deviceToken },
            )
        }.getOrNull() ?: return false
        var client = NotificationsApiClient(record.lastWorkingBaseUrl, http)
        if (check(app, client, record.lookupId, record.profileId, mayPost = true)) return true
        if (record.candidateAddresses.size < 2) return false
        val best = runCatching { CandidateAddressRacer(http).selectBestBaseUrl(record.candidateAddresses) }.getOrNull()
            ?: return false
        if (best == record.lastWorkingBaseUrl) return false
        client = NotificationsApiClient(best, http)
        return check(app, client, record.lookupId, record.profileId, mayPost = true)
    }
}

/** the platform's periodic job: the app avoids WorkManager */
class NotificationCheckJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val ok = runCatching { NotificationCenter.backgroundCheck(applicationContext) }.getOrDefault(false)
            // periodic jobs come back on their own; no reschedule on a failed try
            if (!ok) android.util.Log.d("SoulSyncNotify", "background check didn't reach the server")
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        job?.cancel()
        return false
    }
}
