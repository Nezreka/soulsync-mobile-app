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

/**
 * Races SoulSync candidate addresses over cert-pinned HTTPS, prioritizing LAN over WAN/Custom
 * (DESIGN-CONNECT v0 Section 3 & Section 5).
 */
class CandidateAddressRacer(
    private val pinnedClient: OkHttpClient,
    private val healthPath: String = "/api/health",
) {
    suspend fun selectBestBaseUrl(candidates: List<CandidateAddress>): String? = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext null

        val lanCandidates = candidates.filter { it.scope == CandidateScope.LAN }
        val nonLanCandidates = candidates.filter { it.scope != CandidateScope.LAN }

        // Phase 1: Try LAN candidates first (lowest latency, local network)
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
            pinnedClient.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 401
            }
        } catch (_: Exception) {
            false
        }
    }
}
