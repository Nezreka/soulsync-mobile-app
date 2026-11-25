package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Flag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiResult
import net.ssync.player.OwnedBook
import net.ssync.player.PlayerApiClient
import net.ssync.player.PodcastShow
import net.ssync.player.PodcastSub
import net.ssync.player.YtChannel
import net.ssync.player.YtVideo

private enum class YtTab(val label: String) {
    FOR_YOU("For you"),
    DOWNLOADED("Downloaded"),
    CHANNELS("Channels"),
    CATEGORIES("Categories"),
}

@Composable
fun YouTubeScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onChannelClick: (String, String) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var channels by remember { mutableStateOf<List<YtChannel>>(emptyList()) }
    var downloads by remember { mutableStateOf<List<YtVideo>>(emptyList()) }
    var latest by remember { mutableStateOf<List<YtVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(YtTab.FOR_YOU) }

    LaunchedEffect(api) {
        isLoading = true
        when (val r = api.fetchYoutubeOverview()) {
            is MusicApiResult.Success -> {
                channels = r.data.channels
                downloads = r.data.downloadedVideos
                latest = r.data.latest
            }
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    val videos = remember(downloads, latest) { (downloads + latest).distinctBy { it.id } }

    if (isLoading) {
        LoadingBox()
        return
    }
    if (channels.isEmpty() && videos.isEmpty()) {
        EmptyBox("No YouTube channels or videos yet.")
        return
    }

    fun thumbOf(v: YtVideo): String? =
        api.artwork.resolve(v.thumbnail) ?: api.artwork.forItem("youtube", v.id)

    fun playYt(v: YtVideo) {
        coordinator.playVideo(
            ActivePlayable(
                itemType = "youtube",
                itemId = v.id,
                title = v.title,
                subtitle = v.channelTitle.orEmpty(),
                artworkUrl = thumbOf(v),
                streamUrl = api.streamUrl("youtube", v.id),
                isVideo = true,
                parentId = v.channelId,
            ),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrowseHeaderAndTabs(
            title = "YouTube",
            tabs = YtTab.entries.map { it to it.label },
            selected = tab,
            onSelect = { tab = it },
        )

        when (tab) {
            YtTab.FOR_YOU -> {
                val lead = downloads.firstOrNull() ?: latest.firstOrNull()
                val byChannel = remember(channels, videos) {
                    channels.mapNotNull { ch ->
                        val chVids = videos.filter { it.channelId == ch.id || it.channelTitle == ch.title }
                        if (chVids.isNotEmpty()) ch to chVids else null
                    }.take(10)
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), modifier = Modifier.fillMaxSize()) {
                    if (lead != null) {
                        item(key = "yt-lead") {
                            HeroSpotlight(
                                title = lead.title,
                                subtitle = lead.channelTitle.orEmpty(),
                                backdropUrl = thumbOf(lead),
                                eyebrow = "Latest Video",
                                onPlay = { playYt(lead) },
                            )
                        }
                    }
                    if (downloads.isNotEmpty()) {
                        item(key = "yt-downloads") {
                            MediaRail(
                                title = "Latest Downloads (${downloads.size})",
                                actionLabel = "All",
                                onAction = { tab = YtTab.DOWNLOADED },
                            ) {
                                items(downloads.take(24), key = { "dl-${it.id}" }) { v ->
                                    WideMediaCard(
                                        title = v.title,
                                        subtitle = v.channelTitle,
                                        artworkUrl = thumbOf(v),
                                        onClick = { playYt(v) },
                                    )
                                }
                            }
                        }
                    }
                    if (latest.isNotEmpty()) {
                        item(key = "yt-latest") {
                            MediaRail(title = "New From Followed Channels") {
                                items(latest.take(24), key = { "lat-${it.id}" }) { v ->
                                    WideMediaCard(
                                        title = v.title,
                                        subtitle = v.channelTitle,
                                        artworkUrl = thumbOf(v),
                                        onClick = {
                                            val cid = v.channelId
                                            if (v.downloaded) {
                                                playYt(v)
                                            } else if (!cid.isNullOrBlank()) {
                                                onChannelClick(cid, v.channelTitle ?: "Channel")
                                            } else {
                                                playYt(v)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (channels.isNotEmpty()) {
                        item(key = "yt-ch") {
                            MediaRail(
                                title = "Followed Channels (${channels.size})",
                                actionLabel = "All",
                                onAction = { tab = YtTab.CHANNELS },
                            ) {
                                items(channels, key = { it.id }) { ch ->
                                    SquareMediaCard(
                                        title = ch.title,
                                        subtitle = "${ch.downloadedCount} downloaded",
                                        artworkUrl = api.artwork.resolve(ch.avatar),
                                        width = 112.dp,
                                        onClick = { onChannelClick(ch.id, ch.title) },
                                    )
                                }
                            }
                        }
                    }
                    byChannel.forEach { (ch, chVids) ->
                        item(key = "yt-ch-${ch.id}") {
                            MediaRail(
                                title = ch.title,
                                actionLabel = "Channel",
                                onAction = { onChannelClick(ch.id, ch.title) },
                            ) {
                                items(chVids.take(12), key = { "${ch.id}-${it.id}" }) { v ->
                                    WideMediaCard(
                                        title = v.title,
                                        subtitle = ch.title,
                                        artworkUrl = thumbOf(v),
                                        onClick = { playYt(v) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            YtTab.DOWNLOADED -> {
                val dlList = downloads.ifEmpty { videos }
                if (dlList.isEmpty()) {
                    EmptyBox("No downloaded YouTube videos yet.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                        items(dlList, key = { it.id }) { v ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 5.dp)
                                    .dpadFocusable(RoundedCornerShape(12.dp))
                                    .clickable { playYt(v) },
                                colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                                border = BorderStroke(1.dp, SoulSyncColors.Line),
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SoulSyncArtwork(
                                        url = thumbOf(v),
                                        contentDescription = v.title,
                                        modifier = Modifier.size(width = 118.dp, height = 68.dp).clip(RoundedCornerShape(8.dp)),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(v.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(v.channelTitle.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            YtTab.CHANNELS -> {
                if (channels.isEmpty()) {
                    EmptyBox("No YouTube channels followed yet.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (LocalIsTv.current) 220.dp else 140.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(channels, key = { it.id }) { ch ->
                            SquareMediaCard(
                                title = ch.title,
                                subtitle = "${ch.downloadedCount} videos",
                                artworkUrl = api.artwork.resolve(ch.avatar),
                                onClick = { onChannelClick(ch.id, ch.title) },
                            )
                        }
                    }
                }
            }

            YtTab.CATEGORIES -> {
                val quick = remember(videos) { videos.filter { (it.durationSeconds ?: 0) in 1..599 } }
                val sitDown = remember(videos) { videos.filter { (it.durationSeconds ?: 0) in 600..1799 } }
                val longVids = remember(videos) { videos.filter { (it.durationSeconds ?: 0) >= 1800 } }
                LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), modifier = Modifier.fillMaxSize()) {
                    if (quick.isNotEmpty()) {
                        item {
                            MediaRail("Quick watches · under 10 minutes") {
                                items(quick.take(18), key = { it.id }) { v ->
                                    WideMediaCard(
                                        title = v.title,
                                        subtitle = v.channelTitle,
                                        artworkUrl = thumbOf(v),
                                        onClick = { playYt(v) },
                                    )
                                }
                            }
                        }
                    }
                    if (sitDown.isNotEmpty()) {
                        item {
                            MediaRail("Sit-down watches · 10 to 30 minutes") {
                                items(sitDown.take(18), key = { it.id }) { v ->
                                    WideMediaCard(
                                        title = v.title,
                                        subtitle = v.channelTitle,
                                        artworkUrl = thumbOf(v),
                                        onClick = { playYt(v) },
                                    )
                                }
                            }
                        }
                    }
                    if (longVids.isNotEmpty()) {
                        item {
                            MediaRail("Long watches · 30 minutes or more") {
                                items(longVids.take(18), key = { it.id }) { v ->
                                    WideMediaCard(
                                        title = v.title,
                                        subtitle = v.channelTitle,
                                        artworkUrl = thumbOf(v),
                                        onClick = { playYt(v) },
                                    )
                                }
                            }
                        }
                    }
                    if (quick.isEmpty() && sitDown.isEmpty() && longVids.isEmpty()) {
                        item {
                            MediaRail("All Videos") {
                                items(videos.take(24), key = { it.id }) { v ->
                                    WideMediaCard(
                                        title = v.title,
                                        subtitle = v.channelTitle,
                                        artworkUrl = thumbOf(v),
                                        onClick = { playYt(v) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YouTubeChannelScreen(
    channelId: String,
    fallbackTitle: String,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var videos by remember { mutableStateOf<List<YtVideo>>(emptyList()) }
    var channelName by remember { mutableStateOf(fallbackTitle) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(channelId, api) {
        isLoading = true
        when (val r = api.fetchYoutubeChannel(channelId)) {
            is MusicApiResult.Success -> {
                channelName = r.data.channel?.title ?: fallbackTitle
                videos = r.data.videos
            }
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(channelName, style = MaterialTheme.typography.headlineMedium) }
        items(videos, key = { it.id }) { v ->
            SimpleTrackRow(
                title = v.title,
                subtitle = v.publishedAt.orEmpty(),
                artworkUrl = api.artwork.resolve(v.thumbnail),
                onClick = {
                    coordinator.playVideo(
                        ActivePlayable(
                            itemType = "youtube",
                            itemId = v.id,
                            title = v.title,
                            subtitle = channelName,
                            artworkUrl = api.artwork.resolve(v.thumbnail),
                            streamUrl = api.streamUrl("youtube", v.id),
                            isVideo = true,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
fun PodcastsScreen(
    api: PlayerApiClient,
    onPodcastClick: (PodcastSub) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var subs by remember { mutableStateOf<List<PodcastSub>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        when (val r = api.fetchPodcastSubs()) {
            is MusicApiResult.Success -> subs = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    if (subs.isEmpty()) {
        EmptyBox("No podcast subscriptions yet.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (LocalIsTv.current) 176.dp else 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(subs, key = { it.id }) { sub ->
            SquareMediaCard(
                title = sub.title,
                subtitle = sub.author,
                artworkUrl = api.artwork.resolve(sub.artworkUrl),
                fallbackIcon = Icons.Default.Podcasts,
                onClick = { onPodcastClick(sub) },
            )
        }
    }
}

@Composable
fun PodcastDetailScreen(
    sub: PodcastSub,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var show by remember { mutableStateOf<PodcastShow?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(sub.feedUrl, api) {
        isLoading = true
        when (val r = api.fetchPodcastShow(sub.feedUrl)) {
            is MusicApiResult.Success -> show = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    val episodes = show?.episodes.orEmpty()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoulSyncArtwork(
                    url = api.artwork.resolve(show?.artworkUrl ?: sub.artworkUrl),
                    contentDescription = sub.title,
                    fallbackIcon = Icons.Default.Podcasts,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(sub.title, style = MaterialTheme.typography.titleLarge)
                    Text(sub.author.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        items(episodes) { ep ->
            // the episode's own audio: the server has no podcast stream to fall back on
            val stream = ep.enclosureUrl
            // the web player's key for an episode, so a resume carries between them
            val episodeKey = (ep.guid ?: ep.enclosureUrl ?: ep.title).take(200)
            var showEpReport by remember(ep.guid ?: ep.title) { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SimpleTrackRow(
                        title = ep.title,
                        subtitle = listOfNotNull(ep.pubDate, ep.durationSeconds?.let { "${it / 60}m" }).joinToString(" • "),
                        artworkUrl = api.artwork.resolve(ep.artworkUrl ?: sub.artworkUrl),
                        onClick = {
                            if (stream == null) return@SimpleTrackRow
                            coordinator.playAudio(
                                ActivePlayable(
                                    itemType = "podcast_episode",
                                    itemId = episodeKey,
                                    title = ep.title,
                                    subtitle = sub.title,
                                    artworkUrl = api.artwork.resolve(ep.artworkUrl ?: sub.artworkUrl),
                                    streamUrl = stream,
                                ),
                            )
                        },
                    )
                }
                androidx.compose.material3.IconButton(onClick = { showEpReport = true }) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = "Report issue",
                        tint = SoulSyncColors.TextSecondary,
                    )
                }
            }
            if (showEpReport) {
                ReportIssueDialog(
                    api = api,
                    itemType = "podcast_episode",
                    itemId = episodeKey,
                    title = ep.title,
                    subtitle = sub.title,
                    onDismiss = { showEpReport = false },
                )
            }
        }
    }
}

@Composable
fun AudiobooksScreen(
    api: PlayerApiClient,
    onBookClick: (OwnedBook) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var books by remember { mutableStateOf<List<OwnedBook>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        when (val r = api.fetchAudiobooks()) {
            is MusicApiResult.Success -> books = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    if (books.isEmpty()) {
        EmptyBox("No audiobooks in your library yet.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (LocalIsTv.current) 176.dp else 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(books, key = { it.asin }) { book ->
            SquareMediaCard(
                title = book.title,
                subtitle = book.author,
                artworkUrl = api.artwork.resolve(book.coverUrl),
                fallbackIcon = Icons.Default.Headphones,
                onClick = { onBookClick(book) },
            )
        }
    }
}

@Composable
fun AudiobookDetailScreen(
    book: OwnedBook,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SoulSyncArtwork(
            url = api.artwork.resolve(book.coverUrl),
            contentDescription = book.title,
            fallbackIcon = Icons.Default.Headphones,
            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(book.title, style = MaterialTheme.typography.headlineMedium)
        if (!book.author.isNullOrBlank()) {
            Text("By ${book.author}", style = MaterialTheme.typography.titleMedium, color = SoulSyncColors.TextSoft)
        }
        if (!book.narrator.isNullOrBlank()) {
            Text("Narrated by ${book.narrator}", style = MaterialTheme.typography.bodyMedium)
        }
        book.runtimeMinutes?.let { mins ->
            Text("${mins / 60}h ${mins % 60}m", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                coordinator.playAudio(
                    ActivePlayable(
                        itemType = "audiobook",
                        itemId = book.asin,
                        title = book.title,
                        subtitle = book.author.orEmpty(),
                        artworkUrl = api.artwork.resolve(book.coverUrl),
                        streamUrl = api.streamUrl("audiobook", book.asin),
                    ),
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = SoulSyncColors.Accent,
                contentColor = SoulSyncColors.OnAccent,
            ),
            modifier = Modifier.fillMaxWidth().height(50.dp).dpadFocusable(),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Listen")
        }
        Spacer(modifier = Modifier.height(10.dp))
        var showBookReport by remember(book.asin) { mutableStateOf(false) }
        androidx.compose.material3.OutlinedButton(
            onClick = { showBookReport = true },
            modifier = Modifier.fillMaxWidth().height(46.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Flag,
                contentDescription = null,
                tint = SoulSyncColors.TextSecondary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Report issue", color = SoulSyncColors.TextPrimary)
        }
        if (showBookReport) {
            ReportIssueDialog(
                api = api,
                itemType = "audiobook",
                itemId = book.asin,
                title = book.title,
                subtitle = book.author.orEmpty(),
                onDismiss = { showBookReport = false },
            )
        }
    }
}
