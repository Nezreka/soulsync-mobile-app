package net.ssync.pairing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ssync.network.MusicProfile
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.storage.PairedServerRecord

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onPaired: (PairedServerRecord) -> Unit,
    noticeBanner: String? = null,
) {
    val step by viewModel.step.collectAsStateWithLifecycle()

    LaunchedEffect(step) {
        val paired = step as? PairingStep.Paired
        if (paired != null) {
            onPaired(paired.record)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SoulSyncColors.BgPrimary)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.soulsync_logo),
                contentDescription = "SoulSync Logo",
                modifier = Modifier.size(112.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Direct, private music streaming from your server",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            if (!noticeBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = noticeBanner,
                    color = SoulSyncColors.Warning,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (val current = step) {
                is PairingStep.CodeEntry -> CodeEntryCard(
                    state = current,
                    onCodeChanged = viewModel::onCodeChanged,
                    onSubmit = viewModel::submitCode,
                )
                is PairingStep.ConfirmFingerprint -> FingerprintVerifyCard(
                    state = current,
                    onConfirm = viewModel::confirmFingerprintAndRedeem,
                    onReject = viewModel::rejectFingerprint,
                )
                is PairingStep.ProfilePick -> ProfilePickCard(
                    state = current,
                    onProfileClick = viewModel::selectProfile,
                    onSubmitCredentials = viewModel::submitProfileCredentials,
                    onDismissCredentials = viewModel::dismissCredentialPrompt,
                )
                is PairingStep.Paired -> {
                    CircularProgressIndicator(color = SoulSyncColors.Accent)
                }
            }
        }
    }
}

@Composable
private fun CodeEntryCard(
    state: PairingStep.CodeEntry,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
        border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Pair with your SoulSync server",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Open SoulSync Settings -> Remote Connect and enter the 16-character pairing code.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = state.codeInput,
                onValueChange = onCodeChanged,
                label = { Text("Pairing code") },
                placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                singleLine = true,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().dpadFocusable(),
            )
            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = state.errorMessage,
                    color = SoulSyncColors.Danger,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onSubmit,
                enabled = !state.isBusy && state.codeInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoulSyncColors.Accent,
                    contentColor = SoulSyncColors.OnAccent,
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp).dpadFocusable(),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SoulSyncColors.OnAccent,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun FingerprintVerifyCard(
    state: PairingStep.ConfirmFingerprint,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
        border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = SoulSyncColors.Accent,
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Verify server fingerprint",
                style = MaterialTheme.typography.titleMedium,
            )
            state.answer.serverName?.let { name ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = name, color = SoulSyncColors.TextPrimary, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Confirm this code matches the fingerprint shown in SoulSync Settings:",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SoulSyncColors.BgPrimary, RoundedCornerShape(10.dp))
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.answer.formattedFingerprint,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 2.sp,
                    color = SoulSyncColors.AccentLight,
                )
            }
            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = state.errorMessage, color = SoulSyncColors.Danger)
            }
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(1f).height(48.dp).dpadFocusable(),
                ) {
                    Text("Doesn't match")
                }
                Button(
                    onClick = onConfirm,
                    enabled = !state.isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                    modifier = Modifier.weight(1f).height(48.dp).dpadFocusable(),
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SoulSyncColors.OnAccent,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Matches")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePickCard(
    state: PairingStep.ProfilePick,
    onProfileClick: (MusicProfile) -> Unit,
    onSubmitCredentials: (String) -> Unit,
    onDismissCredentials: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
        border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = "Choose your music profile", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Profiles permitted for music apps on this server:",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.errorMessage != null && state.selectedProfile == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = state.errorMessage, color = SoulSyncColors.Danger)
            }
            Spacer(modifier = Modifier.height(14.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(240.dp),
            ) {
                items(state.profiles, key = { it.idString }) { profile ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .dpadFocusable(RoundedCornerShape(12.dp))
                            .clickable(enabled = !state.isBusy) { onProfileClick(profile) },
                        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.CardRaised),
                        border = BorderStroke(1.dp, SoulSyncColors.Line),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = SoulSyncColors.Accent,
                            )
                            Spacer(modifier = Modifier.size(12.dp))
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (profile.requiresPin || profile.requiresPassword) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Protected profile",
                                    tint = SoulSyncColors.TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val promptProfile = state.selectedProfile
    if (promptProfile != null) {
        var secret by remember(promptProfile.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismissCredentials,
            containerColor = SoulSyncColors.BgSecondary,
            title = {
                Text(
                    text = if (promptProfile.requiresPin) {
                        "Enter PIN for ${promptProfile.name}"
                    } else {
                        "Enter password for ${promptProfile.name}"
                    },
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(if (promptProfile.requiresPin) "PIN" else "Password") },
                        modifier = Modifier.fillMaxWidth().dpadFocusable(),
                    )
                    if (state.errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = state.errorMessage, color = SoulSyncColors.Danger)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSubmitCredentials(secret) },
                    enabled = !state.isBusy && secret.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                    modifier = Modifier.dpadFocusable(),
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissCredentials,
                    modifier = Modifier.dpadFocusable(),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}
