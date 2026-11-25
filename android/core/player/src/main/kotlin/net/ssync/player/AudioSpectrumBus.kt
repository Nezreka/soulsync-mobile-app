package net.ssync.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live 64-band log-spaced FFT + time-domain oscilloscope + spectral-flux beat analyzer
 * fed directly from Media3 ExoPlayer's `TeeAudioProcessor` (zero microphone permissions needed;
 * works identically on Android TV / Shield TV and phones for FLAC, MP3, Opus, AAC, local & streamed).
 *
 * Mirrors `webui/src/lib/visualizer/analysis.ts` (40 Hz .. 16 kHz log bands + spectral-flux onset).
 */
@UnstableApi
object AudioSpectrumBus : TeeAudioProcessor.AudioBufferSink {

    const val BAND_COUNT = 64
    const val WAVE_COUNT = 256
    private const val FFT_SIZE = 1024
    private const val F_MIN = 40.0
    private const val F_MAX = 16000.0

    @Volatile
    private var sampleRate: Int = 48000

    @Volatile
    private var channelCount: Int = 2

    @Volatile
    private var encoding: Int = C.ENCODING_PCM_16BIT

    @Volatile
    private var lastAudioNanos: Long = 0L

    private val ringLock = Any()
    private val pcmRing = FloatArray(FFT_SIZE)
    private var ringWritePos = 0

    // Pre-allocated FFT & smoothing workspaces (UI/render thread only via readFrame)
    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val magBins = FloatArray(FFT_SIZE / 2)
    private val bandLo = IntArray(BAND_COUNT)
    private val bandHi = IntArray(BAND_COUNT)
    private var cachedBandSr = 0

    // Spectral-flux beat state (ported from analysis.ts)
    private val prevBands = FloatArray(BAND_COUNT)
    private val smoothBands = FloatArray(BAND_COUNT)
    private val fluxHist = FloatArray(86)
    private var fluxHistPos = 0
    private var fluxHistCount = 0
    private var beatEnv = 0f

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = sampleRateHz.coerceAtLeast(8000)
        this.channelCount = channelCount.coerceAtLeast(1)
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val remaining = buffer.remaining()
        if (remaining <= 0) return
        val dup = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val ch = channelCount
        synchronized(ringLock) {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val frameBytes = ch * 2
                    val frames = remaining / frameBytes
                    val step = if (frames > FFT_SIZE) frames / FFT_SIZE else 1
                    var f = 0
                    while (f < frames) {
                        val byteOffset = f * frameBytes
                        var sum = 0f
                        for (c in 0 until ch) {
                            sum += dup.getShort(byteOffset + c * 2).toFloat() / 32768f
                        }
                        pcmRing[ringWritePos] = sum / ch
                        ringWritePos = (ringWritePos + 1) and (FFT_SIZE - 1)
                        f += step
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    val frameBytes = ch * 4
                    val frames = remaining / frameBytes
                    val step = if (frames > FFT_SIZE) frames / FFT_SIZE else 1
                    var f = 0
                    while (f < frames) {
                        val byteOffset = f * frameBytes
                        var sum = 0f
                        for (c in 0 until ch) {
                            sum += dup.getFloat(byteOffset + c * 4)
                        }
                        pcmRing[ringWritePos] = (sum / ch).coerceIn(-1f, 1f)
                        ringWritePos = (ringWritePos + 1) and (FFT_SIZE - 1)
                        f += step
                    }
                }
                else -> return
            }
            lastAudioNanos = System.nanoTime()
        }
    }

    /**
     * Populates [outFreq] (64 floats in 0..1) and [outWave] (256 floats in -1..1) from live ExoPlayer
     * PCM audio (or gentle desktop `idleSynth` if paused / silent), returning live audio metrics.
     */
    fun sampleFrame(
        isPlaying: Boolean,
        timeSec: Float,
        dtSec: Float,
        outFreq: FloatArray,
        outWave: FloatArray,
    ): AudioFrameMetrics {
        val dt = dtSec.coerceIn(0.001f, 0.1f)
        val now = System.nanoTime()
        val livePcm = isPlaying && (now - lastAudioNanos) < 400_000_000L

        if (!livePcm) {
            // Desktop idleSynth fallback when paused or between tracks
            for (i in 0 until BAND_COUNT) {
                val v = 0.5f + 0.5f * sin(timeSec * 0.6f + i * 0.55f) * sin(timeSec * 0.23f + i * 0.21f)
                val target = (0.15f + v * 0.22f)
                smoothBands[i] = smoothBands[i] + (target - smoothBands[i]) * (dt * 6f).coerceAtMost(1f)
                outFreq[i] = smoothBands[i]
            }
            for (i in 0 until WAVE_COUNT) {
                outWave[i] = 0.20f * sin((i.toFloat() / WAVE_COUNT) * (2f * PI.toFloat()) * 2f + timeSec * 0.8f)
            }
            beatEnv = max(0f, beatEnv - dt * 2.4f)
            return AudioFrameMetrics(
                energy = 0.22f,
                beat = beatEnv,
                onset = false,
                bass = smoothBands[2],
                mid = smoothBands[24],
                treble = smoothBands[50],
                isLive = false,
            )
        }

        // Snapshot ring buffer in chronological order
        synchronized(ringLock) {
            var r = ringWritePos
            for (i in 0 until FFT_SIZE) {
                val s = pcmRing[r]
                fftRe[i] = s * hannWindow[i]
                fftIm[i] = 0f
                r = (r + 1) and (FFT_SIZE - 1)
            }
            // Rising zero-crossing triggered oscilloscope trace (just like desktop scopeTrace)
            var trig = 0
            val searchLimit = FFT_SIZE - WAVE_COUNT - 1
            for (i in 1 until searchLimit) {
                val a = pcmRing[(ringWritePos + i - 1) and (FFT_SIZE - 1)]
                val b = pcmRing[(ringWritePos + i) and (FFT_SIZE - 1)]
                if (a <= 0f && b > 0f) {
                    trig = i
                    break
                }
            }
            for (i in 0 until WAVE_COUNT) {
                outWave[i] = pcmRing[(ringWritePos + trig + i) and (FFT_SIZE - 1)]
            }
        }

        // Fast in-place Radix-2 Cooley-Tukey FFT
        fftRadix2(fftRe, fftIm)

        // Convert to dB-scaled 0..1 magnitudes (matching Web Audio AnalyserNode -85dB..-15dB)
        val half = FFT_SIZE / 2
        val invN = 2.0f / FFT_SIZE.toFloat()
        for (k in 0 until half) {
            val mag = sqrt(fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k]) * invN
            val db = 20.0f * (ln(max(mag, 1e-6f)) / 2.3025851f)
            magBins[k] = ((db + 82f) / 68f).coerceIn(0f, 1f)
        }

        ensureLogBands(sampleRate)

        // Map to 64 log-spaced musical bands + smooth attack/release
        var sumEnergy = 0f
        var flux = 0f
        val attack = (dt * 24f).coerceIn(0.25f, 0.85f)
        val release = (dt * 10f).coerceIn(0.12f, 0.55f)
        for (b in 0 until BAND_COUNT) {
            val lo = bandLo[b]
            val hi = bandHi[b]
            var acc = 0f
            var cnt = 0
            for (k in lo..hi) {
                acc += magBins[k]
                cnt++
            }
            val raw = if (cnt > 0) acc / cnt else 0f
            val alpha = if (raw > smoothBands[b]) attack else release
            val smoothed = smoothBands[b] + (raw - smoothBands[b]) * alpha
            smoothBands[b] = smoothed
            outFreq[b] = smoothed
            sumEnergy += smoothed
            val diff = raw - prevBands[b]
            if (diff > 0f) flux += diff
            prevBands[b] = raw
        }

        val energy = (sumEnergy / BAND_COUNT).coerceIn(0f, 1f)
        flux /= BAND_COUNT.toFloat()
        fluxHist[fluxHistPos] = flux
        fluxHistPos = (fluxHistPos + 1) % fluxHist.size
        if (fluxHistCount < fluxHist.size) fluxHistCount++
        var mean = 0f
        for (i in 0 until fluxHistCount) mean += fluxHist[i]
        mean /= max(1, fluxHistCount)

        val prevBeat = beatEnv
        if (beatEnv < 0.35f && flux > max(0.012f, mean * 1.55f)) {
            beatEnv = 1f
        }
        beatEnv = max(0f, beatEnv - dt * 2.4f)
        val onset = beatEnv > prevBeat + 0.01f

        var bass = 0f
        for (i in 0 until 8) bass += outFreq[i]
        bass /= 8f
        var mid = 0f
        for (i in 16 until 36) mid += outFreq[i]
        mid /= 20f
        var treble = 0f
        for (i in 44 until 64) treble += outFreq[i]
        treble /= 20f

        return AudioFrameMetrics(
            energy = energy,
            beat = beatEnv,
            onset = onset,
            bass = bass,
            mid = mid,
            treble = treble,
            isLive = true,
        )
    }

    private fun ensureLogBands(sr: Int) {
        if (cachedBandSr == sr) return
        cachedBandSr = sr
        val binHz = sr.toDouble() / FFT_SIZE.toDouble()
        val maxBin = FFT_SIZE / 2 - 1
        val ratio = (F_MAX / F_MIN).pow(1.0 / BAND_COUNT.toDouble())
        for (b in 0 until BAND_COUNT) {
            val lo = min(maxBin, max(1, floor((F_MIN * ratio.pow(b.toDouble())) / binHz).toInt()))
            val hi = min(maxBin, max(lo, kotlin.math.ceil((F_MIN * ratio.pow((b + 1).toDouble())) / binHz).toInt() - 1))
            bandLo[b] = lo
            bandHi[b] = hi
        }
    }

    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (-2.0 * PI / len)
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val halfLen = len shr 1
                for (k in 0 until halfLen) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + halfLen] * wRe - im[i + k + halfLen] * wIm
                    val vIm = re[i + k + halfLen] * wIm + im[i + k + halfLen] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + halfLen] = uRe - vRe
                    im[i + k + halfLen] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}

data class AudioFrameMetrics(
    val energy: Float,
    val beat: Float,
    val onset: Boolean,
    val bass: Float,
    val mid: Float,
    val treble: Float,
    val isLive: Boolean,
)
