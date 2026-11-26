package net.ssync.app.player

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ssync.network.CandidateAddressRacer
import net.ssync.network.CertPinningOkHttp
import net.ssync.player.MusicApiResult
import net.ssync.player.OFFLINE_VIDEO_KINDS
import net.ssync.player.OfflineApiClient
import net.ssync.player.OfflineDeviceReport
import net.ssync.player.OfflineMigration
import net.ssync.player.OfflinePendingOp
import net.ssync.player.OfflineReconcile
import net.ssync.player.OfflineSyncEvent
import net.ssync.player.PlayerApiClient
import net.ssync.storage.KeystoreServerVault

/**
 * keeps the phone and the server in step: whether the server can be reached
 * (offline mode), the reconcile loop with /wanted, the device report, and
 * the plays and positions made offline.
 */
object OfflineSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconcileLock = Mutex()

    private val _online = MutableStateFlow(true)
    /** false once the server can't be reached; the app then shows only downloads */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val checkLock = Mutex()
    private val onlineCheck = OnlineCheck(strikesToOffline = 2)

    private val _checking = MutableStateFlow(false)
    /** a look at the server is under way (the try again spinner) */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _lastChecked = MutableStateFlow(0L)
    /** when the server was last asked, 0 before the first look */
    val lastChecked: StateFlow<Long> = _lastChecked.asStateFlow()

    private val _preferredBaseUrl = MutableStateFlow<String?>(null)
    /** another of the server's addresses answered when the current one didn't: the shell moves to it */
    val preferredBaseUrl: StateFlow<String?> = _preferredBaseUrl.asStateFlow()

    private val _lastSync = MutableStateFlow(0L)
    val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

    @Volatile private var attached: OfflineApiClient? = null
    @Volatile private var appContext: Context? = null
    private var reportJob: Job? = null
    private var lastReconcileMs = 0L

    const val RECONCILE_EVERY_MS = 15 * 60 * 1000L

    /** the shell's client: its base url is the one that won the lan/wan race */
    fun attach(context: Context, api: PlayerApiClient) {
        appContext = context.applicationContext
        attached = OfflineApiClient(api.baseUrl, api.pinnedAuthenticatedClient)
    }

    /**
     * a client for background work. with the app closed there is no shell, so
     * it's built from the paired record, trying the last address that worked
     * and then racing the others
     */
    suspend fun client(context: Context): OfflineApiClient? {
        attached?.let { return it }
        val record = runCatching { KeystoreServerVault(context.applicationContext).load() }.getOrNull() ?: return null
        val http = runCatching {
            CertPinningOkHttp.createPinnedClient(
                expectedCertFingerprintSha256 = record.certFingerprintSha256,
                deviceTokenProvider = { record.deviceToken },
            )
        }.getOrNull() ?: return null
        val first = OfflineApiClient(record.lastWorkingBaseUrl, http)
        if (first.reachable() || record.candidateAddresses.size < 2) return first
        val best = runCatching { CandidateAddressRacer(http).selectBestBaseUrl(record.candidateAddresses) }.getOrNull()
        return if (best != null) OfflineApiClient(best, http) else first
    }

    fun setOnline(value: Boolean) {
        _online.value = value
    }

    /**
     * ask the server. the current address first, then every address it gave us
     * (the lan one stops answering the moment you leave home). offline only
     * after two misses in a row; one answer and it's back
     */
    suspend fun checkOnline(context: Context): Boolean = checkLock.withLock {
        _checking.value = true
        try {
            val api = client(context)
            val record = runCatching { KeystoreServerVault(context.applicationContext).load() }.getOrNull()
            val http = api?.http
            val found = onlineCheck.look(
                currentBase = api?.base,
                probeCurrent = { api?.reachable() == true },
                raceAll = {
                    val cands = record?.candidateAddresses.orEmpty()
                    if (http == null || cands.isEmpty()) null
                    else runCatching { CandidateAddressRacer(http).selectBestBaseUrl(cands) }.getOrNull()
                },
            )
            val switchTo = found.switchTo
            if (switchTo != null && http != null) {
                attached = OfflineApiClient(switchTo, http)
                if (record != null && record.lastWorkingBaseUrl != switchTo) {
                    runCatching { KeystoreServerVault(context.applicationContext).save(record.copy(lastWorkingBaseUrl = switchTo)) }
                }
                _preferredBaseUrl.value = switchTo
            }
            val ok = onlineCheck.decide(found.reached, _online.value)
            _online.value = ok
            _lastChecked.value = System.currentTimeMillis()
            found.reached
        } finally {
            _checking.value = false
        }
    }

    /** the shell moved to [preferredBaseUrl]: clear it so the same address can be offered again later */
    fun preferredTaken() {
        _preferredBaseUrl.value = null
    }

    /** the try again button: look now, and catch up if the server's back */
    fun retryNow(context: Context) {
        if (_checking.value) return
        scope.launch {
            val ok = checkOnline(context)
            if (ok) {
                reconcile(context)
                DownloadService.start(context)
            }
        }
    }

    /**
     * the loop the shell runs while the app is open (phones only): on start,
     * on reconnect and every 15 minutes it reconciles; it checks the server
     * every minute, every 15 seconds after a miss
     */
    suspend fun runWhileOpen(context: Context) {
        OfflineDownloads.init(context)
        appContext = context.applicationContext
        DownloadService.start(context)
        var wasOnline = false
        while (true) {
            val ok = checkOnline(context)
            val now = System.currentTimeMillis()
            if (ok && (!wasOnline || now - lastReconcileMs >= RECONCILE_EVERY_MS)) {
                reconcile(context)
            }
            wasOnline = ok
            // a miss is looked at again soon: offline needs two in a row
            delay(if (ok) 60_000L else 15_000L)
        }
    }

    /** the phone's network changed: look again now rather than at the next tick */
    fun onNetworkChanged(context: Context) {
        scope.launch {
            delay(1_500L)
            val before = _online.value
            val ok = checkOnline(context)
            if (ok && !before) reconcile(context)
            if (ok) DownloadService.start(context)
        }
    }

    /** report soon, once, however many changes land meanwhile */
    fun requestReport() {
        val ctx = appContext ?: return
        reportJob?.cancel()
        reportJob = scope.launch {
            delay(3_000L)
            report(ctx)
        }
    }

    suspend fun report(context: Context): Boolean {
        val api = client(context) ?: return false
        val settings = OfflineDownloads.settings.value
        val tasks = OfflineDownloads.tasks.value
        val res = api.report(
            OfflineDeviceReport(
                deviceId = OfflineDownloads.deviceId(context),
                deviceName = OfflineDownloads.deviceName(),
                platform = "android",
                freeBytes = OfflineDownloads.freeBytes(),
                limitBytes = settings.limitBytes,
                usedBytes = tasks.sumOf { it.diskBytes },
                wifiOnly = settings.wifiOnly,
                items = OfflineReconcile.reportItems(tasks),
            ),
        )
        return res is MusicApiResult.Success
    }

    /** one full pass: send what was made offline, do what /wanted says, then report */
    suspend fun reconcile(context: Context) = reconcileLock.withLock {
        OfflineDownloads.init(context)
        val api = client(context) ?: return@withLock
        val deviceId = OfflineDownloads.deviceId(context)
        flushOps(api, deviceId)
        flushEvents(api, deviceId)

        val wanted = api.wanted(deviceId)
        if (wanted is MusicApiResult.Success) {
            val plan = OfflineReconcile.plan(wanted.data, OfflineDownloads.tasks.value)
            if (plan.remove.isNotEmpty()) OfflineDownloads.remove(plan.remove, tellServer = false)
            if (plan.add.isNotEmpty()) {
                OfflineDownloads.enqueue(
                    context,
                    plan.add.map { a ->
                        val raw = a.groupRef()
                        val g = raw?.normalized()
                        OfflineRequest(
                            itemType = a.itemType,
                            itemId = a.itemId,
                            title = a.title.ifBlank { a.itemType },
                            subtitle = a.subtitle.orEmpty(),
                            artwork = OfflineMigration.relativeArtwork(a.artwork),
                            isVideo = a.itemType in OFFLINE_VIDEO_KINDS,
                            quality = a.quality,
                            parentId = g?.id,
                            groupType = g?.type.orEmpty(),
                            groupId = g?.id.orEmpty(),
                            groupTitle = g?.title.orEmpty(),
                            groupArtwork = OfflineMigration.relativeArtwork(g?.artwork),
                            sortIndex = (raw?.seasonNumber ?: 0) * 1000,
                            reason = a.reason,
                        )
                    },
                )
            }
        }

        // titles, groups and this phone's rules, as the server sees them
        val devices = api.devices()
        if (devices is MusicApiResult.Success) {
            val mine = devices.data.firstOrNull { it.deviceId == deviceId }
            if (mine != null) {
                OfflineDownloads.update { OfflineReconcile.mergeDeviceInfo(it, mine) }
                OfflineDownloads.setRules(mine.rules)
            }
        }
        if (report(context)) {
            lastReconcileMs = System.currentTimeMillis()
            _lastSync.value = lastReconcileMs
        }
    }

    private suspend fun flushEvents(api: OfflineApiClient, deviceId: String) {
        val pending = OfflineDownloads.pendingEvents()
        if (pending.isEmpty()) return
        for (batch in pending.chunked(200)) {
            if (api.sync(deviceId, batch) is MusicApiResult.Success) OfflineDownloads.eventsSent(batch) else return
        }
    }

    private suspend fun flushOps(api: OfflineApiClient, deviceId: String) {
        val pending = OfflineDownloads.pendingOps()
        if (pending.isEmpty()) return
        val done = mutableListOf<OfflinePendingOp>()
        val legacy = PlayerApiClient(api.base, api.http)
        for (op in pending) {
            val res = when (op.op) {
                "wish_remove" -> api.wish(deviceId, "remove", op.type, op.id, op.quality)
                "rule_delete" -> api.deleteRule(op.id)
                // the old manager's rows: quality holds its device id
                "legacy_removed" -> legacy.markOfflineDownloadRemoved(op.type, op.id, op.quality)
                else -> MusicApiResult.Success(Unit)
            }
            when (res) {
                is MusicApiResult.Success -> done += op
                // the server no longer knows it: nothing left to undo
                is MusicApiResult.Error -> if (res.message.contains("HTTP 404") || res.message.contains("isn't on the server")) done += op
                MusicApiResult.DeviceRevoked -> return
            }
        }
        OfflineDownloads.opsDone(done)
    }

    // ── plays, positions and watched marks ──

    private fun nowIso(): String = java.time.Instant.now().toString()

    /**
     * keep a play/position/watched mark for later when the server can't take
     * it now. downloaded items also remember it locally (watched first when
     * freeing space, resume where it was)
     */
    fun queueEvent(kind: String, itemType: String, itemId: String, positionS: Double = 0.0, durationS: Double = 0.0) {
        val id = OfflineDownloads.task(itemType, itemId)?.itemId ?: itemId
        OfflineDownloads.addEvent(OfflineSyncEvent(kind, itemType, id, positionS, durationS, nowIso()))
    }

    /** local bookkeeping for a downloaded item, online or not */
    fun noteLocal(itemType: String, itemId: String, positionS: Double, durationS: Double, watched: Boolean) {
        if (OfflineDownloads.task(itemType, itemId) == null) return
        OfflineDownloads.savePosition(itemType, itemId, positionS, durationS)
        if (watched) OfflineDownloads.markWatched(itemType, itemId)
    }

    /** send the queue now (the connection came back mid-session) */
    fun flushSoon(context: Context) {
        scope.launch {
            val api = client(context) ?: return@launch
            flushEvents(api, OfflineDownloads.deviceId(context))
        }
    }
}
