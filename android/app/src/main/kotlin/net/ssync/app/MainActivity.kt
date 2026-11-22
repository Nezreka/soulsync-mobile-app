package net.ssync.app

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import net.ssync.pairing.PairingScreen
import net.ssync.pairing.PairingViewModel
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.theme.SoulSyncTheme
import net.ssync.player.SoulSyncPlaybackService
import net.ssync.storage.KeystoreServerVault
import net.ssync.storage.PairedServerRecord

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SoulSyncTheme {
                SoulSyncRoot()
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
    DisposableEffect(context) {
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

    if (!loaded) {
        Box(
            modifier = Modifier.fillMaxSize().background(SoulSyncColors.BgPrimary),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = SoulSyncColors.Accent)
        }
        return
    }

    val activeRecord = pairedRecord
    if (activeRecord == null) {
        val pairingViewModel = remember(vault, revokedNotice) { PairingViewModel(vault = vault) }
        PairingScreen(
            viewModel = pairingViewModel,
            noticeBanner = revokedNotice,
            onPaired = { saved ->
                revokedNotice = null
                pairedRecord = saved
            },
        )
    } else {
        MusicHomeScreen(
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
