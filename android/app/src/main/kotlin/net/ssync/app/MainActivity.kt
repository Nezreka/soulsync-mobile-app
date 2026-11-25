package net.ssync.app

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import net.ssync.app.player.PlayerShell
import net.ssync.connect.RendezvousClient
import net.ssync.pairing.PairingScreen
import net.ssync.pairing.PairingViewModel
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.theme.SoulSyncTheme
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.TvFocusIndication
import net.ssync.pairing.ui.isAndroidTv
import net.ssync.player.SoulSyncPlaybackService
import net.ssync.storage.KeystoreServerVault
import net.ssync.storage.PairedServerRecord

class MainActivity : ComponentActivity() {
    @OptIn(
        androidx.compose.material3.ExperimentalMaterial3Api::class,
        androidx.compose.foundation.ExperimentalFoundationApi::class,
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val isTv = remember { isAndroidTv() }
            val baseDensity = LocalDensity.current
            val effectiveDensity = remember(baseDensity, isTv) {
                if (isTv) {
                    // Keep exact 1:1 native TV pixel density (2.0f on Shield TV 1080p->4K) so
                    // text, borders, and logos NEVER suffer blurry fractional subpixel resampling;
                    // gently scale typography (0.90f) for crisp 10-foot cinema proportions.
                    Density(
                        density = baseDensity.density,
                        fontScale = baseDensity.fontScale * 0.90f,
                    )
                } else {
                    baseDensity
                }
            }
            SoulSyncTheme {
                val baseRipple = androidx.compose.material3.LocalRippleConfiguration.current
                val tvRipple = remember {
                    androidx.compose.material3.RippleConfiguration(
                        color = androidx.compose.ui.graphics.Color.White,
                        rippleAlpha = androidx.compose.material.ripple.RippleAlpha(
                            draggedAlpha = 0.20f,
                            focusedAlpha = 0.14f,
                            hoveredAlpha = 0.14f,
                            pressedAlpha = 0.28f,
                        ),
                    )
                }
                CompositionLocalProvider(
                    LocalIsTv provides isTv,
                    LocalDensity provides effectiveDensity,
                    androidx.compose.material3.LocalRippleConfiguration provides if (isTv) tvRipple else baseRipple,
                    androidx.compose.material3.LocalMinimumInteractiveComponentSize provides if (isTv) androidx.compose.ui.unit.Dp.Unspecified else androidx.compose.ui.unit.Dp(48f),
                ) {
                    SoulSyncRoot()
                }
            }
        }
    }
}

@Composable
private fun SoulSyncRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember(context) { KeystoreServerVault(context.applicationContext) }

    var loaded by remember { mutableStateOf(false) }
    var pairedRecord by remember { mutableStateOf<PairedServerRecord?>(null) }
    var revokedNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vault) {
        pairedRecord = vault.load()
        loaded = true
    }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context, pairedRecord) {
        if (pairedRecord == null) {
            controller = null
            onDispose { }
        } else {
            val token = SessionToken(context, ComponentName(context, SoulSyncPlaybackService::class.java))
            val future: ListenableFuture<MediaController> = MediaController.Builder(context, token).buildAsync()
            future.addListener(
                {
                    runCatching { controller = future.get() }
                },
                ContextCompat.getMainExecutor(context),
            )
            onDispose {
                MediaController.releaseFuture(future)
                controller = null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (loaded) {
            val activeRecord = pairedRecord
            if (activeRecord == null) {
                val pairingViewModel = remember(vault, revokedNotice) {
                    PairingViewModel(
                        vault = vault,
                        rendezvousClient = RendezvousClient(baseUrl = BuildConfig.CONNECT_BASE_URL),
                    )
                }
                PairingScreen(
                    viewModel = pairingViewModel,
                    noticeBanner = revokedNotice,
                    onPaired = { saved ->
                        revokedNotice = null
                        pairedRecord = saved
                    },
                )
            } else {
                PlayerShell(
                    record = activeRecord,
                    mediaController = controller,
                    onDeviceRevoked = {
                        scope.launch {
                            vault.clear()
                            revokedNotice = "This device was revoked in SoulSync. Pair again to continue listening."
                            pairedRecord = null
                        }
                    },
                    onUnpair = {
                        scope.launch {
                            vault.clear()
                            revokedNotice = null
                            pairedRecord = null
                        }
                    },
                )
            }
        }
        SoulSyncBootOverlay(ready = loaded)
    }
}
