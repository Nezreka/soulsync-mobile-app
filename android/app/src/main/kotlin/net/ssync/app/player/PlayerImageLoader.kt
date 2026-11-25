package net.ssync.app.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision
import net.ssync.pairing.theme.SoulSyncColors
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

val LocalSoulSyncImageLoader = staticCompositionLocalOf<ImageLoader> {
    error("LocalSoulSyncImageLoader not provided")
}

/**
 * Host-aware Coil ImageLoader:
 * - Requests targeting the paired SoulSync server use `pinnedClient` (leaf cert pin + Bearer token).
 * - Requests targeting external public image CDNs (YouTube `i.ytimg.com`, TMDB `image.tmdb.org`,
 *   podcast RSS artwork hosts) use a clean system-CA OkHttpClient with zero Bearer token leakage.
 */
fun createPinnedImageLoader(
    context: Context,
    serverBaseUrl: String,
    pinnedClient: OkHttpClient,
): ImageLoader {
    val serverHost = serverBaseUrl.toHttpUrlOrNull()?.host?.lowercase()
    val externalClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val routingFactory = Call.Factory { request ->
        val reqHost = request.url.host.lowercase()
        if (serverHost != null && reqHost == serverHost) {
            pinnedClient.newCall(request)
        } else {
            externalClient.newCall(request)
        }
    }

    return ImageLoader.Builder(context)
        .callFactory(routingFactory)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.28)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(freshImageCacheDir(context))
                .maxSizeBytes(256L * 1024L * 1024L)
                .build()
        }
        .respectCacheHeaders(false)
        .allowHardware(true)
        .allowRgb565(false)
        .crossfade(160)
        .build()
}

private const val IMAGE_CACHE_PREFIX = "soulsync_img_cache"

/**
 * the artwork cache of THIS install of the app. pictures are kept by their
 * address for as long as the cache lives (the server's cache headers are not
 * consulted), so an update that changes how artwork is asked for, or art that
 * was replaced on the server, kept showing the old pictures. every update of
 * the app starts a new cache and removes the ones before it.
 */
private fun freshImageCacheDir(context: Context): File {
    val stamp = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
    val current = File(context.cacheDir, "${IMAGE_CACHE_PREFIX}_$stamp")
    runCatching {
        context.cacheDir.listFiles { f -> f.name.startsWith(IMAGE_CACHE_PREFIX) && f.name != current.name }
            ?.forEach { it.deleteRecursively() }
    }
    return current
}

/**
 * High-performance 60fps artwork renderer using `AsyncImage` (no SubcomposeLayout overhead in
 * LazyColumn/LazyRow) with `Precision.INEXACT` and explicit `maxPx` decode cap so 4K backdrops
 * never thrash TV RAM or trigger GC pauses while scrolling.
 */
@Composable
fun SoulSyncArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
    secondaryFallbackUrl: String? = null,
    fallbackIcon: ImageVector = Icons.Default.Movie,
    contentScale: ContentScale = ContentScale.Crop,
    maxPx: Int = 720,
) {
    val loader = LocalSoulSyncImageLoader.current
    val context = LocalContext.current
    val candidates = remember(url, fallbackUrl, secondaryFallbackUrl) {
        listOfNotNull(url, fallbackUrl, secondaryFallbackUrl)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    if (candidates.isEmpty()) {
        Box(
            modifier = modifier.background(SoulSyncColors.CardRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = SoulSyncColors.TextSecondary,
            )
        }
    } else {
        var attemptIdx by remember(candidates) { mutableStateOf(0) }
        val activeUrl = candidates.getOrNull(attemptIdx)
        Box(
            modifier = modifier.background(SoulSyncColors.CardRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (activeUrl == null) {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = contentDescription,
                    tint = SoulSyncColors.TextSecondary,
                )
            } else {
                val request = remember(context, activeUrl, maxPx) {
                    ImageRequest.Builder(context)
                        .data(activeUrl)
                        .size(maxPx)
                        .precision(Precision.INEXACT)
                        .crossfade(160)
                        .build()
                }
                AsyncImage(
                    model = request,
                    imageLoader = loader,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    onError = { attemptIdx += 1 },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
