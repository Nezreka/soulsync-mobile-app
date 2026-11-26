package net.ssync.pairing

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.ssync.connect.LookupOutcome
import net.ssync.connect.PairingCode
import net.ssync.connect.PairingLink
import net.ssync.connect.RendezvousClient
import net.ssync.connect.VerifiedServerAnswer
import net.ssync.network.AdmitClient
import net.ssync.network.AdmitResult
import net.ssync.network.CandidateAddressRacer
import net.ssync.network.CertPinningOkHttp
import net.ssync.network.MusicProfile
import net.ssync.storage.PairedServerRecord
import net.ssync.storage.ServerVault

sealed interface PairingStep {
    data class CodeEntry(
        val codeInput: String = "",
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
    ) : PairingStep

    data class ConfirmFingerprint(
        val code: PairingCode,
        val answer: VerifiedServerAnswer,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
    ) : PairingStep

    data class ProfilePick(
        val answer: VerifiedServerAnswer,
        val baseUrl: String,
        val unprofiledToken: String,
        val profiles: List<MusicProfile>,
        val selectedProfile: MusicProfile? = null,
        val isBusy: Boolean = false,
        val errorMessage: String? = null,
    ) : PairingStep

    data class Paired(val record: PairedServerRecord) : PairingStep
}

class PairingViewModel(
    private val vault: ServerVault,
    private val rendezvousClient: RendezvousClient = RendezvousClient(),
) : ViewModel() {

    private val _step = MutableStateFlow<PairingStep>(PairingStep.CodeEntry())
    val step: StateFlow<PairingStep> = _step.asStateFlow()

    // the fingerprint a scanned link named: the relay's answer must match it
    private var linkFingerprint: String? = null
    private var work: Job? = null

    fun onCodeChanged(value: String) {
        val current = _step.value as? PairingStep.CodeEntry ?: return
        linkFingerprint = null
        _step.value = current.copy(codeInput = value, errorMessage = null)
    }

    /** a scanned soulsync://pair link: fill the code in and look it up. the
     *  user still confirms the fingerprint, as with a typed code. */
    fun startFromLink(link: PairingLink) {
        work?.cancel()
        linkFingerprint = link.fingerprint
        _step.value = PairingStep.CodeEntry(codeInput = link.codeText)
        submitCode(keepLinkFingerprint = true)
    }

    /** a link that couldn't be read: say so over the normal code boxes */
    fun showLinkProblem(message: String) {
        work?.cancel()
        linkFingerprint = null
        _step.value = PairingStep.CodeEntry(errorMessage = message)
    }

    fun submitCode() = submitCode(keepLinkFingerprint = false)

    private fun submitCode(keepLinkFingerprint: Boolean) {
        val current = _step.value as? PairingStep.CodeEntry ?: return
        if (!keepLinkFingerprint) linkFingerprint = null
        val parsed = PairingCode.parse(current.codeInput).getOrElse {
            _step.value = current.copy(
                errorMessage = "Enter the 16-character pairing code shown in SoulSync Settings.",
            )
            return
        }

        _step.value = current.copy(isBusy = true, errorMessage = null)
        val expected = linkFingerprint
        work = viewModelScope.launch {
            when (val outcome = rendezvousClient.lookup(parsed)) {
                is LookupOutcome.Verified -> {
                    if (expected != null && expected != outcome.answer.fingerprint) {
                        _step.value = PairingStep.CodeEntry(
                            errorMessage = "The server that answered isn't the one in the QR code, so pairing stopped. " +
                                "Make a new code in SoulSync and try again.",
                        )
                        return@launch
                    }
                    _step.value = PairingStep.ConfirmFingerprint(
                        code = parsed,
                        answer = outcome.answer,
                    )
                }
                is LookupOutcome.RateLimited -> {
                    _step.value = current.copy(
                        isBusy = false,
                        errorMessage = "Too many attempts. Try again in ${outcome.retryAfterSeconds}s.",
                    )
                }
                is LookupOutcome.VersionMismatch -> {
                    _step.value = current.copy(isBusy = false, errorMessage = outcome.userMessage)
                }
                is LookupOutcome.Unreachable -> {
                    _step.value = current.copy(isBusy = false, errorMessage = outcome.userMessage)
                }
            }
        }
    }

    fun rejectFingerprint() {
        _step.value = PairingStep.CodeEntry(
            errorMessage = "Pairing cancelled because the server fingerprint did not match.",
        )
    }

    fun confirmFingerprintAndRedeem() {
        val current = _step.value as? PairingStep.ConfirmFingerprint ?: return
        _step.value = current.copy(isBusy = true, errorMessage = null)

        work = viewModelScope.launch {
            val pinnedClient = CertPinningOkHttp.createPinnedClient(
                expectedCertFingerprintSha256 = current.answer.certFingerprintSha256,
            )
            val racer = CandidateAddressRacer(pinnedClient)
            val baseUrl = racer.selectBestBaseUrl(current.answer.candidateAddresses)
            if (baseUrl == null) {
                _step.value = current.copy(
                    isBusy = false,
                    errorMessage = LookupOutcome.DEFAULT_UNREACHABLE_MESSAGE,
                )
                return@launch
            }

            val admit = AdmitClient(baseUrl = baseUrl, pinnedClient = pinnedClient)
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Device" }
            val redeem = admit.redeemCode(current.code, deviceName)
            if (redeem !is AdmitResult.Success) {
                _step.value = PairingStep.CodeEntry(
                    errorMessage = LookupOutcome.DEFAULT_UNREACHABLE_MESSAGE,
                )
                return@launch
            }

            when (val listResult = admit.fetchProfilePickList(redeem.data.token)) {
                is AdmitResult.Success -> {
                    _step.value = PairingStep.ProfilePick(
                        answer = current.answer,
                        baseUrl = baseUrl,
                        unprofiledToken = redeem.data.token,
                        profiles = listResult.data,
                    )
                }
                AdmitResult.UnprofiledTokenExpired -> resetToCodeEntryExpired()
                AdmitResult.InvalidCredentials,
                is AdmitResult.Failed,
                -> _step.value = current.copy(
                    isBusy = false,
                    errorMessage = "Could not load profiles from your SoulSync server.",
                )
            }
        }
    }

    fun selectProfile(profile: MusicProfile) {
        val current = _step.value as? PairingStep.ProfilePick ?: return
        if (profile.requiresPin || profile.requiresPassword) {
            _step.value = current.copy(selectedProfile = profile, errorMessage = null)
        } else {
            completeProfilePick(profile = profile, pin = null, password = null)
        }
    }

    fun dismissCredentialPrompt() {
        val current = _step.value as? PairingStep.ProfilePick ?: return
        _step.value = current.copy(selectedProfile = null, errorMessage = null)
    }

    fun submitProfileCredentials(secret: String) {
        val current = _step.value as? PairingStep.ProfilePick ?: return
        val profile = current.selectedProfile ?: return
        val pin = if (profile.requiresPin) secret else null
        val password = if (profile.requiresPassword) secret else null
        completeProfilePick(profile = profile, pin = pin, password = password)
    }

    private fun completeProfilePick(profile: MusicProfile, pin: String?, password: String?) {
        val current = _step.value as? PairingStep.ProfilePick ?: return
        _step.value = current.copy(isBusy = true, errorMessage = null)

        work = viewModelScope.launch {
            val pinnedClient = CertPinningOkHttp.createPinnedClient(
                expectedCertFingerprintSha256 = current.answer.certFingerprintSha256,
            )
            val admit = AdmitClient(baseUrl = current.baseUrl, pinnedClient = pinnedClient)
            when (val res = admit.pickProfile(current.unprofiledToken, profile.idInt, pin, password)) {
                is AdmitResult.Success -> {
                    val record = PairedServerRecord(
                        lookupId = current.answer.lookupId,
                        serverPubkey = current.answer.identity.publicKeyBase64Url,
                        certFingerprintSha256 = current.answer.certFingerprintSha256,
                        candidateAddresses = current.answer.candidateAddresses,
                        lastWorkingBaseUrl = current.baseUrl,
                        deviceToken = res.data.deviceToken,
                        serverName = current.answer.serverName,
                        profileId = res.data.profileId,
                        profileName = res.data.profileName,
                    )
                    vault.save(record)
                    _step.value = PairingStep.Paired(record)
                }
                AdmitResult.UnprofiledTokenExpired -> resetToCodeEntryExpired()
                AdmitResult.InvalidCredentials -> {
                    _step.value = current.copy(
                        isBusy = false,
                        errorMessage = if (profile.requiresPin) "Incorrect PIN. Try again." else "Incorrect password. Try again.",
                    )
                }
                is AdmitResult.Failed -> {
                    _step.value = current.copy(isBusy = false, errorMessage = res.message)
                }
            }
        }
    }

    private fun resetToCodeEntryExpired() {
        _step.value = PairingStep.CodeEntry(
            errorMessage = "Your pairing session expired. Generate a new code in SoulSync Settings.",
        )
    }
}
