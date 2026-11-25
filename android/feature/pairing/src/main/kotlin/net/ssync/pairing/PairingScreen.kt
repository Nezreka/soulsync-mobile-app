package net.ssync.pairing

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.ssync.network.MusicProfile
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.storage.PairedServerRecord

private val PROFILE_GRADIENTS = listOf(
    listOf(Color(0xFF7C3AED), Color(0xFF4F46E5)),
    listOf(Color(0xFFEC4899), Color(0xFFF43F5E)),
    listOf(Color(0xFF06B6D4), Color(0xFF2563EB)),
    listOf(Color(0xFFF59E0B), Color(0xFFEA580C)),
    listOf(Color(0xFF10B981), Color(0xFF0D9488)),
    listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
)

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

    val isProfilePick = step is PairingStep.ProfilePick
    val activeStepIndex = when (step) {
        is PairingStep.CodeEntry -> 0
        is PairingStep.ConfirmFingerprint -> 1
        is PairingStep.ProfilePick, is PairingStep.Paired -> 2
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        SoulSyncColors.Accent.copy(alpha = 0.24f),
                        Color(0xFF0B0E18),
                        SoulSyncColors.BgPrimary,
                    ),
                    radius = 1350f,
                ),
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isProfilePick) 920.dp else 560.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.soulsync_logo),
                contentDescription = "SoulSync Logo",
                modifier = Modifier.size(if (isProfilePick) 82.dp else 94.dp),
            )

            Spacer(modifier = Modifier.height(14.dp))

            OnboardingStepIndicator(activeIndex = activeStepIndex)

            if (!noticeBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SoulSyncColors.Warning.copy(alpha = 0.14f))
                        .border(1.dp, SoulSyncColors.Warning.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = noticeBanner,
                        color = SoulSyncColors.Warning,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

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
                is PairingStep.ProfilePick -> ProfilePickStage(
                    state = current,
                    onProfileClick = viewModel::selectProfile,
                    onSubmitCredentials = viewModel::submitProfileCredentials,
                    onDismissCredentials = viewModel::dismissCredentialPrompt,
                )
                is PairingStep.Paired -> {
                    CircularProgressIndicator(color = SoulSyncColors.AccentLight)
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepIndicator(activeIndex: Int) {
    val steps = listOf("Pair", "Verify", "Profile")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        steps.forEachIndexed { idx, label ->
            val isCurrent = idx == activeIndex
            val isDone = idx < activeIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            isCurrent -> Color.White
                            isDone -> Color.White.copy(alpha = 0.12f)
                            else -> Color.White.copy(alpha = 0.04f)
                        },
                    )
                    .border(
                        1.dp,
                        when {
                            isCurrent -> Color.White
                            isDone -> Color.White.copy(alpha = 0.24f)
                            else -> Color.White.copy(alpha = 0.10f)
                        },
                        RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isCurrent -> Color(0xFF0A0C14)
                                isDone -> Color(0xFF22C55E)
                                else -> Color.White.copy(alpha = 0.16f)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(11.dp),
                        )
                    } else {
                        Text(
                            text = "${idx + 1}",
                            color = if (isCurrent) Color.White else SoulSyncColors.TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                Text(
                    text = label,
                    color = if (isCurrent) Color(0xFF0A0C14) else Color.White.copy(alpha = if (isDone) 0.85f else 0.55f),
                    fontSize = 11.5.sp,
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                )
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
    val cleanChars = state.codeInput.uppercase().filter { it.isLetterOrDigit() }.take(16)
    val groups = (0 until 4).map { g ->
        val start = g * 4
        if (start < cleanChars.length) cleanChars.substring(start, minOf(start + 4, cleanChars.length)) else ""
    }
    val activeGroup = (cleanChars.length / 4).coerceAtMost(3)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xF5141A2B), Color(0xF50B0F1A)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
            .padding(horizontal = 28.dp, vertical = 26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Connect to SoulSync",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Enter the 16-character pairing code from your server",
                color = SoulSyncColors.TextSecondary,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Subtle breadcrumb pill showing where to find the code
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "SoulSync Web  ›  Settings  ›  Remote Connect",
                    color = SoulSyncColors.AccentLight,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Live 4-segment cinema pairing code preview tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                groups.forEachIndexed { idx, grp ->
                    val isCurrent = idx == activeGroup && cleanChars.length < 16
                    val isFilled = grp.length == 4
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                when {
                                    isFilled -> SoulSyncColors.Accent.copy(alpha = 0.18f)
                                    isCurrent -> Color.White.copy(alpha = 0.09f)
                                    else -> Color.White.copy(alpha = 0.04f)
                                },
                            )
                            .border(
                                width = if (isCurrent) 1.5.dp else 1.dp,
                                color = when {
                                    isCurrent -> SoulSyncColors.AccentLight
                                    isFilled -> SoulSyncColors.Accent.copy(alpha = 0.55f)
                                    else -> Color.White.copy(alpha = 0.12f)
                                },
                                shape = RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = grp.padEnd(4, '•'),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            letterSpacing = 2.sp,
                            color = if (grp.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.25f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = state.codeInput,
                onValueChange = onCodeChanged,
                label = { Text("Pairing code", color = SoulSyncColors.TextSecondary) },
                placeholder = { Text("XXXX-XXXX-XXXX-XXXX", color = Color.White.copy(alpha = 0.28f)) },
                singleLine = true,
                enabled = !state.isBusy,
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!state.isBusy && state.codeInput.isNotBlank()) onSubmit()
                    },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SoulSyncColors.AccentLight,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    focusedContainerColor = Color.White.copy(alpha = 0.05f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.03f),
                    cursorColor = SoulSyncColors.AccentLight,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().dpadFocusable(RoundedCornerShape(14.dp)),
            )

            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = state.errorMessage,
                    color = SoulSyncColors.Danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onSubmit,
                enabled = !state.isBusy && state.codeInput.isNotBlank(),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF0A0C14),
                    disabledContainerColor = Color.White.copy(alpha = 0.12f),
                    disabledContentColor = Color.White.copy(alpha = 0.38f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .dpadFocusable(RoundedCornerShape(999.dp), scaleOnFocus = true),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color(0xFF0A0C14),
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
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
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { confirmFocus.requestFocus() }
    }

    val fpSegments = remember(state.answer.formattedFingerprint) {
        state.answer.formattedFingerprint
            .split("-", " ", ":")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(state.answer.formattedFingerprint) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xF5141A2B), Color(0xF50B0F1A)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
            .padding(horizontal = 28.dp, vertical = 26.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Luminous verified shield emblem
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                SoulSyncColors.Accent.copy(alpha = 0.36f),
                                SoulSyncColors.Accent.copy(alpha = 0.10f),
                            ),
                        ),
                    )
                    .border(1.5.dp, SoulSyncColors.AccentLight.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = SoulSyncColors.AccentLight,
                    modifier = Modifier.size(34.dp),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Verify your server",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                textAlign = TextAlign.Center,
            )

            state.answer.serverName?.takeIf { it.isNotBlank() }?.let { name ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E)),
                    )
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Confirm this security code matches the fingerprint shown in SoulSync Settings:",
                color = SoulSyncColors.TextSecondary,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // High-contrast segmented fingerprint tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fpSegments.forEach { seg ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SoulSyncColors.Accent.copy(alpha = 0.14f))
                            .border(1.5.dp, SoulSyncColors.AccentLight.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = seg,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            letterSpacing = 2.sp,
                            color = Color.White,
                        )
                    }
                }
            }

            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = state.errorMessage,
                    color = SoulSyncColors.Danger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedButton(
                    onClick = onReject,
                    enabled = !state.isBusy,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .dpadFocusable(RoundedCornerShape(999.dp), scaleOnFocus = true),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = SoulSyncColors.TextSoft, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Doesn't match", color = SoulSyncColors.TextSoft, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = onConfirm,
                    enabled = !state.isBusy,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoulSyncColors.Accent,
                        contentColor = SoulSyncColors.OnAccent,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .focusRequester(confirmFocus)
                        .dpadFocusable(RoundedCornerShape(999.dp), scaleOnFocus = true),
                ) {
                    if (state.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SoulSyncColors.OnAccent,
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Matches", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePickStage(
    state: PairingStep.ProfilePick,
    onProfileClick: (MusicProfile) -> Unit,
    onSubmitCredentials: (String) -> Unit,
    onDismissCredentials: () -> Unit,
) {
    val firstProfileFocus = remember { FocusRequester() }
    LaunchedEffect(state.profiles.size) {
        if (state.profiles.isNotEmpty() && state.selectedProfile == null) {
            delay(80L)
            runCatching { firstProfileFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Who's watching?",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Select your profile to continue",
            color = SoulSyncColors.TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )

        if (state.errorMessage != null && state.selectedProfile == null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = state.errorMessage,
                color = SoulSyncColors.Danger,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(34.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(state.profiles, key = { _, p -> p.idString }) { index, profile ->
                val gradient = PROFILE_GRADIENTS[index % PROFILE_GRADIENTS.size]
                ProfileBubbleCard(
                    profile = profile,
                    gradientColors = gradient,
                    enabled = !state.isBusy,
                    modifier = if (index == 0) Modifier.focusRequester(firstProfileFocus) else Modifier,
                    onClick = { onProfileClick(profile) },
                )
            }
        }

        if (state.isBusy && state.selectedProfile == null) {
            Spacer(modifier = Modifier.height(20.dp))
            CircularProgressIndicator(
                color = SoulSyncColors.AccentLight,
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
            )
        }
    }

    val promptProfile = state.selectedProfile
    if (promptProfile != null) {
        val idx = state.profiles.indexOfFirst { it.idString == promptProfile.idString }.coerceAtLeast(0)
        val gradient = PROFILE_GRADIENTS[idx % PROFILE_GRADIENTS.size]
        ProfileCredentialDialog(
            profile = promptProfile,
            gradientColors = gradient,
            isBusy = state.isBusy,
            errorMessage = state.errorMessage,
            onSubmit = onSubmitCredentials,
            onDismiss = onDismissCredentials,
        )
    }
}

@Composable
private fun ProfileBubbleCard(
    profile: MusicProfile,
    gradientColors: List<Color>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileBubbleScale",
    )
    val initial = profile.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "S"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(136.dp)
            .zIndex(if (isFocused) 2f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        Box(
            modifier = Modifier.size(108.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Outer glow ring when focused
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(gradientColors))
                    .border(
                        width = if (isFocused) 3.5.dp else 1.5.dp,
                        color = if (isFocused) Color.White else Color.White.copy(alpha = 0.22f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            if (profile.requiresPin || profile.requiresPassword) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xEB0E121E))
                        .border(1.5.dp, Color.White.copy(alpha = 0.28f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Protected profile",
                        tint = SoulSyncColors.AccentLight,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = profile.name,
            color = if (isFocused) Color.White else SoulSyncColors.TextSoft,
            fontSize = 15.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProfileCredentialDialog(
    profile: MusicProfile,
    gradientColors: List<Color>,
    isBusy: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var secret by remember(profile.idString) { mutableStateOf("") }
    val firstDigitFocus = remember { FocusRequester() }

    LaunchedEffect(profile.idString, profile.requiresPin) {
        if (profile.requiresPin) {
            delay(80L)
            runCatching { firstDigitFocus.requestFocus() }
        }
    }

    // Clear PIN automatically when server returns an error so user can re-enter immediately
    LaunchedEffect(errorMessage) {
        if (errorMessage != null && profile.requiresPin) {
            secret = ""
        }
    }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 390.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFA14192A), Color(0xFA0C101B)),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp))
                .onPreviewKeyEvent { ev ->
                    if (!profile.requiresPin || isBusy || ev.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    val digit = when (ev.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_0, android.view.KeyEvent.KEYCODE_NUMPAD_0 -> "0"
                        android.view.KeyEvent.KEYCODE_1, android.view.KeyEvent.KEYCODE_NUMPAD_1 -> "1"
                        android.view.KeyEvent.KEYCODE_2, android.view.KeyEvent.KEYCODE_NUMPAD_2 -> "2"
                        android.view.KeyEvent.KEYCODE_3, android.view.KeyEvent.KEYCODE_NUMPAD_3 -> "3"
                        android.view.KeyEvent.KEYCODE_4, android.view.KeyEvent.KEYCODE_NUMPAD_4 -> "4"
                        android.view.KeyEvent.KEYCODE_5, android.view.KeyEvent.KEYCODE_NUMPAD_5 -> "5"
                        android.view.KeyEvent.KEYCODE_6, android.view.KeyEvent.KEYCODE_NUMPAD_6 -> "6"
                        android.view.KeyEvent.KEYCODE_7, android.view.KeyEvent.KEYCODE_NUMPAD_7 -> "7"
                        android.view.KeyEvent.KEYCODE_8, android.view.KeyEvent.KEYCODE_NUMPAD_8 -> "8"
                        android.view.KeyEvent.KEYCODE_9, android.view.KeyEvent.KEYCODE_NUMPAD_9 -> "9"
                        else -> null
                    }
                    when {
                        digit != null && secret.length < 8 -> {
                            val next = secret + digit
                            secret = next
                            if (next.length == 4) onSubmit(next)
                            true
                        }
                        ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DEL -> {
                            if (secret.isNotEmpty()) secret = secret.dropLast(1)
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 26.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Mini avatar badge
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(gradientColors))
                        .border(2.dp, Color.White.copy(alpha = 0.28f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = profile.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "S",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = profile.name,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (profile.requiresPin) "Enter your PIN" else "Enter your password",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 13.sp,
                )

                Spacer(modifier = Modifier.height(18.dp))

                if (profile.requiresPin) {
                    // Plex-style glowing PIN dots
                    val dotCount = maxOf(4, secret.length)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(dotCount) { i ->
                            val filled = i < secret.length
                            Box(
                                modifier = Modifier
                                    .size(if (filled) 15.dp else 13.dp)
                                    .clip(CircleShape)
                                    .background(if (filled) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.14f))
                                    .border(
                                        1.5.dp,
                                        if (filled) Color.White else Color.White.copy(alpha = 0.25f),
                                        CircleShape,
                                    ),
                            )
                        }
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = errorMessage,
                            color = SoulSyncColors.Danger,
                            fontSize = 12.5.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // D-pad & touch friendly 3x4 Cinema Numeric Keypad
                    val rows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("CLR", "0", "DEL"),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        rows.forEachIndexed { rIdx, row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                row.forEachIndexed { cIdx, key ->
                                    val keyModifier = if (rIdx == 0 && cIdx == 0) {
                                        Modifier.focusRequester(firstDigitFocus)
                                    } else {
                                        Modifier
                                    }
                                    PinPadKey(
                                        label = key,
                                        enabled = !isBusy,
                                        modifier = keyModifier,
                                        onClick = {
                                            when (key) {
                                                "CLR" -> secret = ""
                                                "DEL" -> if (secret.isNotEmpty()) secret = secret.dropLast(1)
                                                else -> if (secret.length < 8) {
                                                    val next = secret + key
                                                    secret = next
                                                    if (next.length == 4) {
                                                        onSubmit(next)
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f).height(44.dp).dpadFocusable(RoundedCornerShape(999.dp)),
                        ) {
                            Text("Cancel", color = Color.White)
                        }
                        Button(
                            onClick = { onSubmit(secret) },
                            enabled = !isBusy && secret.isNotBlank(),
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoulSyncColors.Accent,
                                contentColor = SoulSyncColors.OnAccent,
                            ),
                            modifier = Modifier.weight(1f).height(44.dp).dpadFocusable(RoundedCornerShape(999.dp)),
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = SoulSyncColors.OnAccent,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Unlock", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth().dpadFocusable(),
                    )
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = errorMessage,
                            color = SoulSyncColors.Danger,
                            fontSize = 12.5.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier.weight(1f).height(44.dp).dpadFocusable(RoundedCornerShape(999.dp)),
                        ) {
                            Text("Cancel", color = Color.White)
                        }
                        Button(
                            onClick = { onSubmit(secret) },
                            enabled = !isBusy && secret.isNotBlank(),
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoulSyncColors.Accent,
                                contentColor = SoulSyncColors.OnAccent,
                            ),
                            modifier = Modifier.weight(1f).height(44.dp).dpadFocusable(RoundedCornerShape(999.dp)),
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = SoulSyncColors.OnAccent,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("Unlock", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinPadKey(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isAction = label == "CLR" || label == "DEL"
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(if (isAction) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.11f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
            .dpadFocusable(CircleShape, scaleOnFocus = true)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (label) {
            "DEL" -> Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Backspace",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
            "CLR" -> Text(
                text = "Clear",
                color = SoulSyncColors.TextSoft,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            else -> Text(
                text = label,
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
