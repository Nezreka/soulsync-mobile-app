package net.ssync.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.AiSubtitleJob
import net.ssync.player.DelaySaveDebouncer
import net.ssync.player.MusicApiResult
import net.ssync.player.ServerSubtitle
import net.ssync.player.SubtitleApiClient
import net.ssync.player.SubtitleCue
import net.ssync.player.SubtitleDelay
import net.ssync.player.SubtitleLanguages
import net.ssync.player.WebVtt

/**
 * the server's subtitles for one video. a picked text subtitle is fetched as
 * WebVTT and drawn by the app against the player clock minus the delay, so
 * the delay really moves it (exoplayer has no subtitle offset). also holds
 * the ai job for the language asked for.
 */
@Stable
class ServerSubtitleState(
    private val client: SubtitleApiClient,
    val itemType: String,
    val itemId: String,
    private val scope: CoroutineScope,
) {
    var subtitles by mutableStateOf<List<ServerSubtitle>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var selected by mutableStateOf<ServerSubtitle?>(null)
        private set
    var cues by mutableStateOf<List<SubtitleCue>>(emptyList())
        private set
    var loadingCues by mutableStateOf(false)
        private set
    var cueError by mutableStateOf<String?>(null)
        private set
    var delayMs by mutableIntStateOf(0)
        private set

    var aiJob by mutableStateOf<AiSubtitleJob?>(null)
        private set
    var aiLang by mutableStateOf("en")
        private set
    var aiMessage by mutableStateOf<String?>(null)
        private set
    var aiAvailable by mutableStateOf(true)
        private set
    var aiWhy by mutableStateOf<String?>(null)
        private set
    var aiBusy by mutableStateOf(false)
        private set

    /** only movies and episodes have a video db id the ai jobs take */
    val canAi: Boolean = (itemType == "movie" || itemType == "episode") && itemId.toIntOrNull() != null

    val textSubtitles: List<ServerSubtitle> get() = subtitles.filter { it.isText }

    // saves outlive the player screen, so a nudge right before closing still lands
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saver = DelaySaveDebouncer(saveScope) { sub, ms ->
        client.saveDelay(itemType, itemId, sub, ms)
    }
    private var cueJob: Job? = null

    suspend fun refreshTracks(): Boolean {
        val res = client.tracks(itemType, itemId)
        if (res is MusicApiResult.Success) {
            subtitles = res.data
            loaded = true
            // keep the picked one current (its delay may have come back from the web player)
            selected?.let { cur -> res.data.firstOrNull { it.id == cur.id } }?.let { selected = it }
            return true
        }
        return false
    }

    /** shows [sub] (null hides the server's subtitles) */
    fun select(sub: ServerSubtitle?) {
        cueJob?.cancel()
        selected = sub
        cues = emptyList()
        cueError = null
        if (sub == null) {
            loadingCues = false
            return
        }
        delayMs = sub.delayMs
        loadingCues = true
        cueJob = scope.launch {
            when (val r = client.webVtt(itemType, itemId, sub.id)) {
                is MusicApiResult.Success -> {
                    cues = WebVtt.parse(r.data)
                    if (cues.isEmpty()) cueError = "This subtitle has no lines."
                }
                is MusicApiResult.Error -> cueError = r.message
                MusicApiResult.DeviceRevoked -> Unit
            }
            loadingCues = false
        }
    }

    fun nudge(deltaMs: Int) = setDelay(delayMs + deltaMs)

    fun setDelay(ms: Int) {
        val sub = selected ?: return
        val clamped = SubtitleDelay.clamp(ms)
        if (clamped == delayMs) return
        delayMs = clamped
        // remember it on the list too, so picking it again keeps the delay
        subtitles = subtitles.map { if (it.id == sub.id) it.copy(delayMs = clamped) else it }
        selected = sub.copy(delayMs = clamped)
        saver.push(sub.id, clamped)
    }

    fun flushDelay() = saver.flush()

    /** the lines to draw at the player's [positionMs] */
    fun linesAt(positionMs: Long): String? {
        if (selected == null) return null
        val on = WebVtt.activeAt(cues, SubtitleDelay.subtitleClock(positionMs, delayMs))
        return if (on.isEmpty()) null else on.joinToString("\n") { it.text }
    }

    /** the job for [lang] as the server has it now */
    suspend fun loadAi(lang: String) {
        if (!canAi) return
        if (lang != aiLang) {
            aiLang = lang
            aiJob = null
            aiMessage = null
        }
        when (val r = client.aiJobs(itemType, itemId.toInt())) {
            is MusicApiResult.Success -> {
                aiAvailable = r.data.available
                aiWhy = r.data.why
                aiJob = r.data.forLanguage(lang)
            }
            is MusicApiResult.Error -> aiMessage = r.message
            MusicApiResult.DeviceRevoked -> Unit
        }
    }

    fun startAi() {
        if (!canAi || aiBusy) return
        val lang = aiLang
        aiBusy = true
        aiMessage = null
        scope.launch {
            when (val r = client.startAiJob(itemType, itemId.toInt(), lang)) {
                is MusicApiResult.Success -> if (lang == aiLang) aiJob = r.data
                is MusicApiResult.Error -> aiMessage = r.message
                MusicApiResult.DeviceRevoked -> Unit
            }
            aiBusy = false
        }
    }

    fun stopAi() {
        val job = aiJob ?: return
        scope.launch {
            when (val r = client.cancelAiJob(job.id)) {
                is MusicApiResult.Success -> loadAi(aiLang)
                is MusicApiResult.Error -> aiMessage = r.message
                MusicApiResult.DeviceRevoked -> Unit
            }
        }
    }

    /**
     * asks every 3 s while the job runs. when it finishes, fetches the tracks
     * again and switches to the new ai subtitle; true when it did.
     */
    suspend fun pollAi(): Boolean {
        while (scope.isActive) {
            val job = aiJob ?: return false
            if (!job.isActive) return false
            delay(3_000L)
            val r = client.aiJobs(itemType, itemId.toInt())
            if (r !is MusicApiResult.Success) continue
            aiAvailable = r.data.available
            aiWhy = r.data.why
            val now = r.data.forLanguage(aiLang) ?: r.data.jobs.firstOrNull { it.id == job.id } ?: continue
            aiJob = now
            if (now.isDone) {
                refreshTracks()
                val made = subtitles.lastOrNull { it.ai && it.isText && SubtitleLanguages.same(it.language, now.language) }
                if (made != null) {
                    select(made)
                    aiMessage = "Now showing ${SubtitleLanguages.label(made)}."
                    return true
                }
                aiMessage = "Done, but the new subtitle isn't showing up yet."
                return false
            }
        }
        return false
    }

    /** the ai subtitle already made for [lang], if there is one */
    fun aiSubtitleFor(lang: String): ServerSubtitle? =
        subtitles.lastOrNull { it.ai && it.isText && SubtitleLanguages.same(it.language, lang) }
}

/** the server subtitle lines over the picture, on the player clock minus the delay */
@Composable
fun ServerSubtitleCues(
    state: ServerSubtitleState,
    positionMs: () -> Long,
    raised: Boolean,
    modifier: Modifier = Modifier,
) {
    var line by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val sel = state.selected
    LaunchedEffect(sel?.id, state.cues, state.delayMs) {
        while (true) {
            line = state.linesAt(positionMs())
            delay(50L)
        }
    }
    val text = line ?: return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = if (raised) 132.dp else 36.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 19.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** show earlier / show later in 100 ms and 1 s steps, and back in sync */
@Composable
fun SubtitleDelayControl(state: ServerSubtitleState) {
    val ms = state.delayMs
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SUBTITLE TIMING", color = SoulSyncColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            Text(
                text = SubtitleDelay.describe(ms),
                color = if (ms == 0) SoulSyncColors.TextSoft else SoulSyncColors.AccentLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DelayPill("-1 s", Modifier.weight(0.7f)) { state.nudge(-SubtitleDelay.BIG_STEP_MS) }
            DelayPill("Show earlier", Modifier.weight(1.2f)) { state.nudge(-SubtitleDelay.SMALL_STEP_MS) }
            DelayPill("Show later", Modifier.weight(1.2f)) { state.nudge(SubtitleDelay.SMALL_STEP_MS) }
            DelayPill("+1 s", Modifier.weight(0.7f)) { state.nudge(SubtitleDelay.BIG_STEP_MS) }
        }
        if (ms != 0) {
            DelayPill("Back in sync", Modifier.fillMaxWidth()) { state.setDelay(0) }
        }
    }
}

@Composable
private fun DelayPill(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, SoulSyncColors.Line, CircleShape)
            .dpadFocusable(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** a server subtitle in the tracks list: "English (AI)" and its small line */
@Composable
fun ServerSubtitleRow(sub: ServerSubtitle, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SoulSyncColors.Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (selected) SoulSyncColors.Accent.copy(alpha = 0.55f) else SoulSyncColors.Line, RoundedCornerShape(10.dp))
            .dpadFocusable(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sub.ai) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = SubtitleLanguages.label(sub),
                color = if (selected) Color.White else SoulSyncColors.TextSoft,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            val detail = SubtitleLanguages.detail(sub)
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    color = if (sub.lowConfidence) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** "make them with ai" for the chosen language, then the job's progress */
@Composable
fun AiSubtitleRow(state: ServerSubtitleState, lang: String, onShow: (ServerSubtitle) -> Unit) {
    LaunchedEffect(lang) { state.loadAi(lang) }
    val job = state.aiJob?.takeIf { state.aiLang == lang }
    val langName = SubtitleLanguages.name(lang)
    val ready = state.aiSubtitleFor(lang)
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SoulSyncColors.Accent.copy(alpha = 0.08f))
            .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = when {
                    job != null && job.isActive -> "Making $langName subtitles with AI"
                    ready != null -> "$langName subtitles made with AI"
                    else -> "Make them with AI"
                }
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val sub = when {
                    job != null && job.isActive -> job.statusLine()
                    ready != null -> if (state.selected?.id == ready.id) "Showing now" else "Ready to show"
                    !state.aiAvailable -> state.aiWhy ?: "This server can't make subtitles with AI."
                    else -> "SoulSync listens to the audio and writes $langName subtitles."
                }
                Text(sub, color = SoulSyncColors.TextSecondary, fontSize = 11.sp)
            }
            when {
                job != null && job.isActive -> AiPill("Stop", Icons.Default.Close) { state.stopAi() }
                ready != null && state.selected?.id != ready.id -> AiPill("Show", null) { onShow(ready) }
                ready == null && state.aiAvailable -> if (state.aiBusy) {
                    CircularProgressIndicator(color = SoulSyncColors.AccentLight, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    AiPill("Make", null) { state.startAi() }
                }
            }
        }
        if (job != null && job.state == "running") {
            LinearProgressIndicator(
                progress = { job.progress.toFloat().coerceIn(0f, 1f) },
                color = SoulSyncColors.AccentLight,
                trackColor = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
        val problem = job?.problem
        val msg = state.aiMessage
        if (job != null && problem != null) {
            Text(problem, color = SoulSyncColors.AccentLight, fontSize = 12.sp)
        }
        if (msg != null) {
            Text(msg, color = SoulSyncColors.AccentLight, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AiPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, SoulSyncColors.Line, CircleShape)
            .dpadFocusable(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
