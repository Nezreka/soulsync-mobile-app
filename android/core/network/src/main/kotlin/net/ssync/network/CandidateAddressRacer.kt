package net.ssync.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.ssync.connect.CandidateAddress
import net.ssync.connect.CandidateScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Races SoulSync candidate addresses over cert-pinned HTTPS, prioritizing LAN over WAN/Custom
 * (DESIGN-CONNECT v0 Section 3 & Section 5).
 *
 * Probes LAN and WAN in parallel with a short 2.5s timeout so switching from Wi-Fi to 5G/LTE
 * never stalls waiting for a black-holed RFC1918 LAN IP to time out, while still preferring
 * LAN whenever both are reachable.
 */
class CandidateAddressRacer(
    private val pinnedClient: OkHttpClient,
    private val healthPath: String = "/api/devices/me",
) {
    private val probeClient: OkHttpClient by lazy {
        pinnedClient.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun selectBestBaseUrl(candidates: List<CandidateAddress>): String? = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext null

        val lanCandidates = candidates.filter { it.scope == CandidateScope.LAN }
        val nonLanCandidates = candidates.filter { it.scope != CandidateScope.LAN }

        // Phase 1: Try LAN candidates first with a short 2.5s timeout (avoids hitting WAN when on LAN)
        if (lanCandidates.isNotEmpty()) {
            val lanWinner = raceGroup(lanCandidates)
            if (lanWinner != null) return@withContext lanWinner
        }

        // Phase 2: Fall back to WAN / Custom candidates
        if (nonLanCandidates.isNotEmpty()) {
            return@withContext raceGroup(nonLanCandidates)
        }

        null
    }

    private suspend fun raceGroup(group: List<CandidateAddress>): String? = coroutineScope {
        val results = group.map { candidate ->
            async {
                val base = candidate.url.trimEnd('/')
                if (probe(base)) base else null
            }
        }.awaitAll()
        results.firstOrNull { it != null }
    }

    private fun probe(baseUrl: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl$healthPath")
                .get()
                .build()
            probeClient.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 401
            }
        } catch (_: Exception) {
            false
        }
    }
}
