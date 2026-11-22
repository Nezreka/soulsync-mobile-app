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
import okhttp3.OkHttpClient

val LocalSoulSyncImageLoader = staticCompositionLocalOf<ImageLoader> {
    error("LocalSoulSyncImageLoader not provided")
}

fun createPinnedImageLoader(context: Context, pinnedClient: OkHttpClient): ImageLoader =
    ImageLoader.Builder(context)
        .okHttpClient(pinnedClient)
        .crossfade(true)
        .build()

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
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = null,
                        tint = SoulSyncColors.TextSecondary.copy(alpha = 0.4f),
                    )
                }
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
