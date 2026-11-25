package net.ssync.app.player

import net.ssync.pairing.ui.dpadFocusable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.CastMember
import net.ssync.player.CrewMember
import net.ssync.player.FranchiseCollection
import net.ssync.player.MusicApiResult
import net.ssync.player.PersonDetail
import net.ssync.player.PlayerApiClient
import net.ssync.player.StudioCollection
import net.ssync.player.VideoCatalog

internal data class ReportIssueTarget(
    val entityType: String, // "movie" | "show" | "episode"
    val entityId: Int,
    val title: String,
)

private data class VideoIssueCategoryOption(
    val id: String,
    val label: String,
    val types: Set<String>,
)

private val VIDEO_ISSUE_CATEGORIES = listOf(
    VideoIssueCategoryOption("playback_issue", "Won't play / buffers / stutters", setOf("movie", "episode")),
    VideoIssueCategoryOption("audio_issue", "Audio problem (sync, language, missing)", setOf("movie", "episode")),
    VideoIssueCategoryOption("subtitle_issue", "Subtitle problem", setOf("movie", "episode")),
    VideoIssueCategoryOption("bad_quality", "Poor video quality", setOf("movie", "episode")),
    VideoIssueCategoryOption("missing_content", "Missing episodes or seasons", setOf("show")),
    VideoIssueCategoryOption("wrong_match", "Matched to the wrong title", setOf("movie", "show")),
    VideoIssueCategoryOption("wrong_metadata", "Wrong details (year, summary, genres…)", setOf("movie", "show", "episode")),
    VideoIssueCategoryOption("wrong_poster", "Wrong poster / artwork", setOf("movie", "show")),
    VideoIssueCategoryOption("duplicate", "Duplicate copies", setOf("movie", "show")),
    VideoIssueCategoryOption("other", "Something else", setOf("movie", "show", "episode")),
)

@Composable
internal fun ReportIssuePillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        border = BorderStroke(1.dp, SoulSyncColors.Line),
        contentPadding = if (compact) {
            PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        } else {
            PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        },
        modifier = modifier.height(if (compact) 38.dp else 40.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
    ) {
        Icon(
            imageVector = Icons.Default.Flag,
            contentDescription = "Report issue",
            tint = SoulSyncColors.TextSoft,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Report issue",
            color = SoulSyncColors.TextSoft,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReportIssueSheet(
    target: ReportIssueTarget,
    api: PlayerApiClient,
    onDeviceRevoked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val categories = remember(target.entityType) {
        VIDEO_ISSUE_CATEGORIES.filter { target.entityType in it.types }
    }
    var selectedCategory by remember(target.entityType) {
        mutableStateOf(categories.firstOrNull()?.id ?: "other")
    }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AdaptiveTvSheet(
        onDismissRequest = { if (!busy) onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Report an issue",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = target.title,
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }

            if (done) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1B3B2B))
                        .border(1.dp, Color(0xFF39A96B), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF7BF1A8))
                    Text(
                        text = "Issue submitted — thanks for flagging it!",
                        color = Color(0xFFD6FFE5),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    text = "WHAT'S WRONG?",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    categories.forEach { cat ->
                        val active = cat.id == selectedCategory
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (active) SoulSyncColors.Accent.copy(alpha = 0.22f)
                                    else SoulSyncColors.Card,
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (active) SoulSyncColors.Accent else SoulSyncColors.Line,
                                    shape = RoundedCornerShape(999.dp),
                                )
                                .clickable { selectedCategory = cat.id }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = cat.label,
                                color = if (active) Color.White else SoulSyncColors.TextSoft,
                                fontSize = 12.5.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Details (optional)") },
                    placeholder = { Text("Timestamps, language, or what went wrong…") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = Color(0xFFFF7A7A),
                        fontSize = 12.5.sp,
                    )
                }

                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        errorMsg = null
                        scope.launch {
                            when (
                                val r = api.submitVideoIssue(
                                    entityType = target.entityType,
                                    entityId = target.entityId,
                                    category = selectedCategory,
                                    title = target.title,
                                    description = description,
                                )
                            ) {
                                is MusicApiResult.Success -> done = true
                                MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                                is MusicApiResult.Error -> errorMsg = r.message
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(46.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = SoulSyncColors.OnAccent,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (busy) "Submitting…" else "Submit Issue",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ── Plex-Style Discovery Rails (Collections, Cast/Director, Studio/Network) ──

internal data class DiscoveryCardItem(
    val key: String,
    val kind: String, // "movie" | "show"
    val libraryId: Int?,
    val tmdbId: Int,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
)

internal data class DiscoveryRail(
    val key: String,
    val title: String,
    val items: List<DiscoveryCardItem>,
)

private data class FeaturedPersonRef(
    val tmdbId: Int,
    val name: String,
    val railTitle: String,
)

private data class FetchedPersonResult(
    val tmdbId: Int,
    val railTitle: String,
    val person: PersonDetail?,
)

@Composable
internal fun rememberRelatedDiscoveryRails(
    kind: String, // "movie" | "show"
    selfId: Int,
    cast: List<CastMember>,
    leadCrew: List<CrewMember>,
    studioOrNetwork: String?,
    franchiseCollection: FranchiseCollection?,
    api: PlayerApiClient,
): List<DiscoveryRail> {
    var rails by remember(kind, selfId) { mutableStateOf<List<DiscoveryRail>>(emptyList()) }

    LaunchedEffect(kind, selfId, cast, leadCrew, studioOrNetwork, franchiseCollection, api) {
        val result = coroutineScope {
            val catalogDeferred = async {
                (api.fetchVideoCatalog() as? MusicApiResult.Success)?.data
            }
            val collectionsDeferred = async {
                (api.fetchCollections() as? MusicApiResult.Success)?.data.orEmpty()
            }

            // Pick up to 3 people: lead director/creator + top 2 cast
            val featuredPeople: List<FeaturedPersonRef> = buildList {
                val seen = mutableSetOf<Int>()
                val firstCrew = leadCrew.firstOrNull { (it.tmdbId ?: 0) > 0 }
                val firstCrewId = firstCrew?.tmdbId
                if (firstCrew != null && firstCrewId != null) {
                    seen += firstCrewId
                    add(FeaturedPersonRef(firstCrewId, firstCrew.name, "More from ${firstCrew.name}"))
                }
                for (member in cast) {
                    if (size >= 3) break
                    val pid = member.tmdbId ?: continue
                    if (pid <= 0 || pid in seen) continue
                    seen += pid
                    add(FeaturedPersonRef(pid, member.name, "More with ${member.name}"))
                }
            }

            val peopleDeferred = featuredPeople.map { ref ->
                async {
                    val p = (api.fetchPerson(ref.tmdbId) as? MusicApiResult.Success)?.data
                    FetchedPersonResult(ref.tmdbId, ref.railTitle, p)
                }
            }

            val catalog: VideoCatalog? = catalogDeferred.await()
            val collections: List<StudioCollection> = collectionsDeferred.await()
            val peopleResults = peopleDeferred.awaitAll()

            val movieById = catalog?.movies.orEmpty().associateBy { it.id }
            val showById = catalog?.shows.orEmpty().associateBy { it.id }
            val ownedMovieByTmdb = catalog?.movies.orEmpty()
                .mapNotNull { m -> m.tmdbId?.let { it to m.id } }.toMap()
            val ownedShowByTmdb = catalog?.shows.orEmpty()
                .mapNotNull { s -> s.tmdbId?.let { it to s.id } }.toMap()
            val selfTmdbId = if (kind == "movie") movieById[selfId]?.tmdbId else showById[selfId]?.tmdbId

            val built = mutableListOf<DiscoveryRail>()

            // 1. TMDB Franchise Collection (movies)
            val franchiseName = franchiseCollection?.name?.trim()
            if (!franchiseName.isNullOrBlank()) {
                val fItems = franchiseCollection.items
                    .filter { it.tmdbId != selfTmdbId }
                    .sortedBy { it.yearText ?: "9999" }
                    .map { t ->
                        val libId = t.libraryId ?: ownedMovieByTmdb[t.tmdbId]
                        DiscoveryCardItem(
                            key = "fr-${t.kind}-${t.tmdbId}",
                            kind = if (t.kind == "show" || t.kind == "tv") "show" else "movie",
                            libraryId = libId,
                            tmdbId = t.tmdbId,
                            title = t.title,
                            subtitle = t.yearText,
                            artworkUrl = if (libId != null) api.artwork.moviePoster(libId) else api.artwork.resolve(t.poster),
                        )
                    }
                if (fItems.isNotEmpty()) {
                    val have = fItems.count { it.libraryId != null }
                    built += DiscoveryRail(
                        key = "franchise-${franchiseCollection.id}",
                        title = "$franchiseName · $have of ${fItems.size} more",
                        items = fItems,
                    )
                }
            }

            // 2. Collection Studio Collections containing this title
            val skipName = franchiseName?.lowercase().orEmpty()
            collections
                .filter { col ->
                    col.mediaType == kind &&
                        selfId in col.members &&
                        (skipName.isEmpty() || col.name.trim().lowercase() != skipName)
                }
                .forEach { col ->
                    val cards = col.members
                        .filter { it != selfId }
                        .mapNotNull { mid ->
                            if (kind == "movie") {
                                val m = movieById[mid] ?: return@mapNotNull null
                                DiscoveryCardItem(
                                    key = "col-${col.id}-m-${m.id}",
                                    kind = "movie",
                                    libraryId = m.id,
                                    tmdbId = m.tmdbId ?: 0,
                                    title = m.title,
                                    subtitle = m.year?.toString(),
                                    artworkUrl = api.artwork.moviePoster(m.id),
                                )
                            } else {
                                val sh = showById[mid] ?: return@mapNotNull null
                                DiscoveryCardItem(
                                    key = "col-${col.id}-s-${sh.id}",
                                    kind = "show",
                                    libraryId = sh.id,
                                    tmdbId = sh.tmdbId ?: 0,
                                    title = sh.title,
                                    subtitle = sh.year?.toString(),
                                    artworkUrl = api.artwork.showPoster(sh.id),
                                )
                            }
                        }
                        .take(20)
                    if (cards.isNotEmpty()) {
                        built += DiscoveryRail(
                            key = "studio-col-${col.id}",
                            title = "In ${col.name}",
                            items = cards,
                        )
                    }
                }

            // 3. More from Director/Creator & Top Cast (owned library titles)
            for (pr in peopleResults) {
                val pid = pr.tmdbId
                val railTitle = pr.railTitle
                val credits = pr.person?.credits.orEmpty()
                val seenKeys = mutableSetOf<String>()
                val cards = mutableListOf<DiscoveryCardItem>()
                for (c in credits) {
                    val cKind = if (c.kind == "show" || c.kind == "tv") "show" else "movie"
                    val libId = c.libraryId
                        ?: if (cKind == "movie") ownedMovieByTmdb[c.tmdbId] else ownedShowByTmdb[c.tmdbId]
                    if (libId == null) continue
                    if (cKind == kind && libId == selfId) continue
                    val dedupeKey = "$cKind:$libId"
                    if (!seenKeys.add(dedupeKey)) continue
                    val artwork = if (cKind == "movie") api.artwork.moviePoster(libId) else api.artwork.showPoster(libId)
                    cards += DiscoveryCardItem(
                        key = "p-$pid-$dedupeKey",
                        kind = cKind,
                        libraryId = libId,
                        tmdbId = c.tmdbId,
                        title = c.title,
                        subtitle = c.yearText ?: c.role,
                        artworkUrl = artwork,
                    )
                }
                if (cards.isNotEmpty()) {
                    built += DiscoveryRail(
                        key = "person-rail-$pid",
                        title = railTitle,
                        items = cards.take(18),
                    )
                }
            }

            // 4. More from Studio (movies) / More on Network (shows)
            val makerLabel = studioOrNetwork?.trim()
            if (!makerLabel.isNullOrBlank()) {
                val needle = makerLabel.lowercase()
                if (kind == "movie") {
                    val matches = catalog?.movies.orEmpty()
                        .filter { it.id != selfId && it.studio?.trim()?.lowercase() == needle }
                        .sortedWith(compareByDescending<net.ssync.player.CatalogMovie> { it.rating ?: 0.0 }.thenByDescending { it.year ?: 0 })
                        .take(18)
                        .map { m ->
                            DiscoveryCardItem(
                                key = "studio-m-${m.id}",
                                kind = "movie",
                                libraryId = m.id,
                                tmdbId = m.tmdbId ?: 0,
                                title = m.title,
                                subtitle = m.year?.toString(),
                                artworkUrl = api.artwork.moviePoster(m.id),
                            )
                        }
                    if (matches.size >= 2) {
                        built += DiscoveryRail(
                            key = "maker-studio",
                            title = "More from $makerLabel",
                            items = matches,
                        )
                    }
                } else {
                    val matches = catalog?.shows.orEmpty()
                        .filter { it.id != selfId && it.network?.trim()?.lowercase() == needle }
                        .sortedWith(compareByDescending<net.ssync.player.CatalogShow> { it.rating ?: 0.0 }.thenByDescending { it.year ?: 0 })
                        .take(18)
                        .map { sh ->
                            DiscoveryCardItem(
                                key = "network-s-${sh.id}",
                                kind = "show",
                                libraryId = sh.id,
                                tmdbId = sh.tmdbId ?: 0,
                                title = sh.title,
                                subtitle = sh.year?.toString(),
                                artworkUrl = api.artwork.showPoster(sh.id),
                            )
                        }
                    if (matches.size >= 2) {
                        built += DiscoveryRail(
                            key = "maker-network",
                            title = "More on $makerLabel",
                            items = matches,
                        )
                    }
                }
            }

            built
        }
        rails = result
    }

    return rails
}

internal fun LazyListScope.relatedDiscoveryRails(
    rails: List<DiscoveryRail>,
    onNavigate: (PlayerDestination) -> Unit,
    onRequestUnowned: (DiscoveryCardItem) -> Unit,
) {
    rails.forEach { rail ->
        item(key = rail.key) {
            MediaRail(title = rail.title) {
                items(rail.items, key = { it.key }) { card ->
                    val isOwned = card.libraryId != null
                    if (isOwned) {
                        PosterCard(
                            title = card.title,
                            subtitle = card.subtitle,
                            artworkUrl = card.artworkUrl,
                            width = 122.dp,
                            onClick = {
                                val lid = card.libraryId!!
                                if (card.kind == "movie") onNavigate(PlayerDestination.MovieDetail(lid))
                                else onNavigate(PlayerDestination.ShowDetail(lid))
                            },
                        )
                    } else {
                        PosterCard(
                            title = card.title,
                            subtitle = card.subtitle ?: "Request",
                            artworkUrl = card.artworkUrl,
                            inLibrary = false,
                            width = 122.dp,
                            onClick = { onRequestUnowned(card) },
                        )
                    }
                }
            }
        }
    }
}
