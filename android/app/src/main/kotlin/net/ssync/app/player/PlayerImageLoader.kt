package net.ssync.app.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import net.ssync.pairing.theme.SoulSyncColors
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
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
        .crossfade(true)
        .build()
}

@Composable
fun SoulSyncArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Default.Movie,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val loader = LocalSoulSyncImageLoader.current
    if (url.isNullOrBlank()) {
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
        SubcomposeAsyncImage(
            model = url,
            imageLoader = loader,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize().background(SoulSyncColors.CardRaised),
                )
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxSize().background(SoulSyncColors.CardRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = contentDescription,
                        tint = SoulSyncColors.TextSecondary,
                    )
                }
            },
        )
    }
}
