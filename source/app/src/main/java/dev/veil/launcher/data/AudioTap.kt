package dev.veil.launcher.data

import dev.veil.launcher.BuildConfig

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads the TV's audio *output* (what's playing through the speakers) for the visualizer. It never records,
 * stores or sends audio and doesn't touch any microphone: Android's Visualizer API only hands over short
 * waveform snapshots ~20 times a second.
 *
 * Analysis (v2, built to follow the song rather than react to noise):
 *  - Our own FFT of the waveform with a Hann window. Android's built-in FFT has no window, so a kick drum
 *    leaks energy across the whole spectrum; windowing keeps bass, mids and treble separate.
 *  - Bands defined in real frequencies (kick 40-160 Hz, mids 300 Hz-2.5 kHz, treble 4-12 kHz), from the
 *    actual sample rate, instead of FFT bin indexes (where all of the bass sat in one bin).
 *  - Beats from *spectral flux* in the low end (how much new bass energy appeared since the last frame)
 *    against an adaptive threshold, which catches kicks/snares far more reliably than "bass is loud".
 *  - A tempo tracker locks onto the song's beat period (60-180 BPM). Detected beats snap the phase; when a
 *    beat is expected but quietly missed, it's filled in, so pulses stay on the beat instead of scattered.
 *
 * Outputs work like MilkDrop's: bass/mid/treble relative to their own recent average (~1.0 = normal for
 * this song, a hit jumps to 1.5-2+).
 *
 * Use through [AudioHub] so there is only ever one reader (two at once block each other).
 */
class AudioTap(private val context: Context) {
    @Volatile var bassRel = 1f; private set
    @Volatile var midRel = 1f; private set
    @Volatile var trebRel = 1f; private set
    /** 0..1 overall loudness relative to the song's peaks. */
    @Volatile var level = 0f; private set
    /** elapsedRealtime of the last beat (detected, or filled in by the tempo tracker). */
    @Volatile var lastBeatAt = 0L; private set
    /** Snare/clap hits: jumps to ~1 on a hit and fades within ~0.1 s. */
    @Volatile var snare = 0f; private set
    /** Hi-hat/cymbal hits: jumps to ~1 on a hit and fades within ~0.08 s. */
    @Volatile var hat = 0f; private set
    /** How bright the sound is right now vs. the last few seconds (-1 = darker, +1 = brighter); follows
     *  vocals, melody and synths rather than drums. Changes slowly. */
    @Volatile var tone = 0f; private set
    /** Estimated tempo, 0 until locked. */
    @Volatile var bpm = 0f; private set
    /** Detection threshold multiplier; lower = more beats. Set from the sensitivity slider (default ~1.35). */
    @Volatile var beatThreshold = 1.35f

    /** 32 log-spaced spectrum bands (40 Hz-16 kHz), auto-gained to 0..1 (for drawing shapes). */
    val spectrum = FloatArray(32)
    /** 64-point waveform, -1..1. */
    val wave = FloatArray(64)

    private var viz: Visualizer? = null
    @Volatile private var lastSignal = 0L
    private var sampleRate = 44_100f

    val permitted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (viz != null) return true
        if (!permitted) return false
        return try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(N)
            // AS_PLAYED: the samples as they really are. NORMALIZED boosts every small chunk of audio to full
            // scale on its own (in 2x steps), which turned the mix down on every kick, up between kicks, and
            // put fake jumps inside a capture that read as hits. Levels are made relative to the song below,
            // so the real volume doesn't matter.
            runCatching { v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED }
            sampleRate = (v.samplingRate / 1000f).takeIf { it > 8_000f } ?: 48_000f
            v.enabled = true
            viz = v
            lastSignal = SystemClock.elapsedRealtime()
            lastTick = 0L
            // Read the newest audio ourselves ~60x a second. Android's own callback only comes 20x a second
            // with 21 ms of audio each time, so 60 % of the song was never seen (kicks fell in the gaps) and
            // every beat was rounded to a 50 ms grid. Each read is the 1024 samples playing right now.
            polling = true
            poller = Thread({ pollLoop(v) }, "veil-audio").apply { isDaemon = true; start() }
            true
        } catch (e: Throwable) {
            viz = null
            false
        }
    }

    private var poller: Thread? = null
    @Volatile private var polling = false

    private fun pollLoop(v: Visualizer) {
        val buf = ByteArray(v.captureSize)
        var failures = 0
        while (polling) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = try { v.getWaveForm(buf) == Visualizer.SUCCESS } catch (e: Throwable) { false }
            if (!polling) return
            if (ok) { failures = 0; analyze(buf) }
            else if (++failures > 30) { useCallback(v); return } // can't read on demand: use Android's callback
            val wait = POLL_MS - (SystemClock.elapsedRealtime() - t0)
            if (wait > 0) try { Thread.sleep(wait) } catch (e: InterruptedException) { return }
        }
    }

    /** Fallback: Android's 20 Hz capture callback (the old way). */
    private fun useCallback(v: Visualizer) = runCatching {
        v.enabled = false
        v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) { if (w != null && polling) analyze(w) }
            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) = Unit
        }, Visualizer.getMaxCaptureRate(), true, false)
        v.enabled = true
    }

    fun stop() {
        polling = false
        poller?.let { it.interrupt(); runCatching { it.join(250) } }
        poller = null
        runCatching { viz?.enabled = false; viz?.release() }
        viz = null
        bassRel = 1f; midRel = 1f; trebRel = 1f; level = 0f; bpm = 0f
        spectrum.fill(0f); wave.fill(0f)
        resetTracking()
    }

    /** The output mix can occasionally hand back pure silence; re-attach if that lasts a while. */
    fun restartIfSilent() {
        if (viz != null && SystemClock.elapsedRealtime() - lastSignal > 4_000) { stop(); start() }
    }

    // ---- FFT --------------------------------------------------------------------------------------------

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val mag = FloatArray(N / 2)
    private val logMag = FloatArray(N / 2)
    private val prevLog = FloatArray(N / 2)
    private val hann = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }

    /** In-place iterative radix-2 FFT of [re]/[im]. */
    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun bin(hz: Float) = (hz * N / sampleRate).toInt().coerceIn(1, N / 2 - 1)

    /** Mean linear magnitude between two frequencies (at least one bin). */
    private fun energy(loHz: Float, hiHz: Float): Float {
        val lo = bin(loHz); val hi = max(lo + 1, bin(hiHz))
        var s = 0f
        for (i in lo until hi) s += mag[i]
        return s / (hi - lo)
    }

    // ---- Analysis ---------------------------------------------------------------------------------------

    private val bandPeak = FloatArray(32) { 0.3f }
    private var longB = 0f; private var longM = 0f; private var longT = 0f
    private var levelPeak = 0.05f

    private var lastTick = 0L
    /** Band edges (FFT bins) of the 32 display bands, worked out once per sample rate. */
    private var bandLo = IntArray(0); private var bandHi = IntArray(0); private var bandsFor = 0f

    // Onset detection
    private val fluxHist = FloatArray(120)  // ~2 s of reads at 60 Hz
    private var fluxN = 0
    private var prevFlux = 0f
    private var lastOnset = 0L

    // Tempo tracking
    private val onsets = ArrayDeque<Long>()
    private var period = 0f          // ms per beat, 0 = unknown
    private var nextBeat = 0L        // predicted time of the next beat
    private var confidence = 0f      // 0..1, how well predictions match detected onsets

    private fun resetTracking() {
        fluxHist.fill(0f); fluxN = 0; prevFlux = 0f; lastOnset = 0L
        prevPrevFlux = 0f; prevAt = 0L; prevKickHit = 0f; pendingPeriod = 0f; pendingVotes = 0; estPeriod = 0f
        onsets.clear(); period = 0f; nextBeat = 0L; confidence = 0f; firedNext = false
        longB = 0f; longM = 0f; longT = 0f
        prevLog.fill(0f)
        snareBand.reset(); hatBand.reset(); prevLogHat.fill(0f); toneMean = 0f; tone = 0f; snare = 0f; hat = 0f
        kickHist.fill(0f); kickEnv = 0f; hitStrength = 0.6f; predictedAt = 0L
    }

    private fun analyze(w: ByteArray) {
        if (w.size < N) return
        val now = SystemClock.elapsedRealtime()
        // Smoothing is by time, not per read, so it looks the same whatever the read rate is
        // (the constants match what the old 20 Hz per-read factors did).
        val dt = if (lastTick == 0L) POLL_MS / 1000f else ((now - lastTick) / 1000f).coerceIn(0.004f, 0.2f)
        lastTick = now
        val quick = 1f - kotlin.math.exp(-dt / 0.048f)      // waveform/spectrum (was 0.35 kept per 50 ms)
        val lvl = 1f - kotlin.math.exp(-dt / 0.055f)        // loudness (was 0.4 kept per 50 ms)

        // Waveform for drawing + windowed copy for the FFT
        var peakAbs = 0f
        for (i in 0 until N) {
            val s = ((w[i].toInt() and 0xFF) - 128) / 128f
            re[i] = s * hann[i]; im[i] = 0f
            if (abs(s) > peakAbs) peakAbs = abs(s)
        }
        val step = N / 64
        for (i in 0 until 64) {
            val s = ((w[i * step].toInt() and 0xFF) - 128) / 128f
            wave[i] += (s - wave[i]) * quick
        }
        if (peakAbs > 0.02f) {
            lastSignal = now
        } else {
            // Silence/pause: let everything settle, and forget the tempo after a while.
            bassRel = 1f; midRel = 1f; trebRel = 1f; snare = 0f; hat = 0f; tone *= 0.95f
            val fade = kotlin.math.exp(-dt / 0.224f)
            level *= fade
            for (i in spectrum.indices) spectrum[i] *= fade
            if (now - lastSignal > 2_500) { onsets.clear(); period = 0f; confidence = 0f; bpm = 0f; nextBeat = 0L; firedNext = false }
            return
        }

        fft()
        for (i in 0 until N / 2) {
            val m = sqrt(re[i] * re[i] + im[i] * im[i]) / (N / 4f)
            mag[i] = m
            logMag[i] = ln(1f + 1000f * m)
        }

        // Display spectrum: 32 log-spaced bands, 40 Hz-16 kHz, per-band auto gain.
        if (octaveFor != sampleRate) {
            octaveFor = sampleRate
            for (i in 1 until N / 2) octave[i] = kotlin.math.log2(i * sampleRate / N)
        }
        if (bandsFor != sampleRate) {
            bandsFor = sampleRate
            bandLo = IntArray(32) { bin(40f * Math.pow(400.0, it / 32.0).toFloat()) }
            bandHi = IntArray(32) { max(bandLo[it] + 1, bin(40f * Math.pow(400.0, (it + 1) / 32.0).toFloat())) }
        }
        val peakFade = kotlin.math.exp(-dt / 6.2f)
        var all = 0f
        for (b in 0 until 32) {
            var sum = 0f
            for (i in bandLo[b] until bandHi[b]) sum += mag[i]
            val e = ln(1f + 1000f * sum / (bandHi[b] - bandLo[b]))
            bandPeak[b] = max(e, bandPeak[b] * peakFade).coerceAtLeast(0.3f)
            val v = (e / bandPeak[b]).coerceIn(0f, 1f)
            spectrum[b] += (v * v - spectrum[b]) * quick // squared: quiet bands stay quiet
            all += v / 32f
        }
        levelPeak = max(all, levelPeak * kotlin.math.exp(-dt / 10f)).coerceAtLeast(0.05f)
        level += ((all / levelPeak).coerceIn(0f, 1f) - level) * lvl

        // Relative band levels (MilkDrop style)
        val rb = energy(40f, 160f); val rm = energy(300f, 2_500f); val rt = energy(4_000f, 12_000f)
        if (longB == 0f) { longB = rb; longM = rm; longT = rt }
        // Bass against the last ~5 s (1.6 s made a moving bass line or a quiet bar look like hits); mids
        // and highs against ~1.6 s, so melody, vocals and cymbals stay lively.
        val avgB = 1f - kotlin.math.exp(-dt / 5f)
        val avgMT = 1f - kotlin.math.exp(-dt / 1.64f)
        longB += (rb - longB) * avgB
        longM += (rm - longM) * avgMT
        longT += (rt - longT) * avgMT
        val bassEnergy = (rb / max(longB, 1e-4f)).coerceAtMost(4f)
        midRel = (rm / max(longM, 1e-4f)).coerceAtMost(4f)
        trebRel = (rt / max(longT, 1e-4f)).coerceAtMost(4f)

        // Spectral flux: new energy in the low end (kick/bass) plus a little of the snare range.
        val kLo = bin(40f); val kHi = bin(200f); val sLo = bin(1_500f); val sHi = bin(5_000f)
        var kick = 0f
        for (i in kLo..kHi) kick += max(0f, logMag[i] - prevLog[i])
        var snare = 0f
        for (i in sLo..sHi) snare += max(0f, logMag[i] - prevLog[i])
        val kickFlux = kick / (kHi - kLo + 1)
        val flux = kickFlux + 0.35f * snare / (sHi - sLo + 1)
        System.arraycopy(logMag, 0, prevLog, 0, N / 2)

        // Kick strength: how far this read's new bass stands out from the last ~2 s (0 = nothing, 1 = a clear hit).
        kickHist[fluxN % kickHist.size] = kickFlux
        val kc = min(fluxN + 1, kickHist.size)
        var kMean = 0f
        for (i in 0 until kc) kMean += kickHist[i]
        kMean /= kc
        var kVar = 0f
        for (i in 0 until kc) { val d = kickHist[i] - kMean; kVar += d * d }
        var kickHit = ((kickFlux - kMean) / (3f * sqrt(kVar / kc) + 1e-4f)).coerceIn(0f, 1.5f)

        // Snares/claps (crack in 1.5-5 kHz) and hi-hats/cymbals (sizzle in 7-14 kHz), each against its own
        // recent average, so every instrument gets its own pulse instead of only the kick.
        val hLo = bin(7_000f); val hHi = bin(14_000f)
        var hatF = 0f
        for (i in hLo..hHi) { hatF += max(0f, logMag[i] - prevLogHat[i]); prevLogHat[i] = logMag[i] }
        val snareHit = snareBand.hit(snare / (sHi - sLo + 1))
        val hatHit = hatBand.hit(hatF / (hHi - hLo + 1))
        this.snare = max(this.snare * kotlin.math.exp(-dt / 0.10f), snareHit)
        this.hat = max(this.hat * kotlin.math.exp(-dt / 0.08f), hatHit)
        if (snareHit > 0.5f && !snareUp) { stSnare++; snareUp = true } else if (snareHit < 0.2f) snareUp = false
        if (hatHit > 0.5f && !hatUp) { stHat++; hatUp = true } else if (hatHit < 0.2f) hatUp = false

        // Brightness of the sound (spectral centroid, in octaves) vs. the last ~6 s.
        var cw = 0f; var cs = 0f
        for (i in bin(150f)..bin(10_000f)) { cw += mag[i] * octave[i]; cs += mag[i] }
        if (cs > 1e-5f) {
            val c = cw / cs
            if (toneMean == 0f) toneMean = c
            toneMean += (c - toneMean) * (1f - kotlin.math.exp(-dt / 6f))
            tone += (((c - toneMean) / 0.6f).coerceIn(-1f, 1f) - tone) * (1f - kotlin.math.exp(-dt / 0.4f))
        }

        // Adaptive threshold: mean + k * std of the last ~2 s.
        fluxHist[fluxN % fluxHist.size] = flux; fluxN++
        val cnt = min(fluxN, fluxHist.size)
        var mean = 0f
        for (i in 0 until cnt) mean += fluxHist[i]
        mean /= cnt
        var varr = 0f
        for (i in 0 until cnt) { val d = fluxHist[i] - mean; varr += d * d }
        val std = sqrt(varr / cnt)
        val k = (0.6f + (beatThreshold - 1f) * 3f).coerceIn(0.6f, 3f) // sensitivity slider: lower = more beats
        val minGap = if (period > 0f && confidence > 0.4f) (period * 0.45f).toLong() else 220L
        // A hit spreads over two reads (each read overlaps the last), and used to count on whichever read
        // first crossed the line, so its time jumped by a read either way. Now it's taken at the read where
        // it peaked (the one before this, once this one is lower): one read later, but always the same spot.
        val isOnset = cnt >= 30 && prevFlux > mean + k * std && prevFlux >= prevPrevFlux && prevFlux > flux &&
            prevFlux > 0.02f && prevAt - lastOnset > minGap
        prevPrevFlux = prevFlux; prevFlux = flux
        val hitAt = prevAt; prevAt = now
        val hitKick = max(prevKickHit, kickHit); prevKickHit = kickHit
        if (isOnset) {
            lastOnset = hitAt
            hitStrength += (hitKick - hitStrength) * 0.2f
            onBeatEvidence(hitAt)
        }

        if (period > 0f && nextBeat > 0L) {
            // Tempo locked: show the beat slightly *before* it's heard. The picture reaches the screen about
            // LEAD_MS after we pass it on (next visualizer frame + Android's and the TV's display pipeline),
            // so this way the pulse lands with the sound instead of just after it.
            if (confidence > 0.5f && !firedNext && now >= nextBeat - LEAD_MS) {
                lastBeatAt = now; firedNext = true; stPredicted++
                predictedAt = now
                kickEnv = max(kickEnv, hitStrength) // the bass pulse starts with the shown beat, at its usual strength
            }
            // The beat went by without a matching hit: it was still shown (on time, full strength) if
            // locked; move on to the next one.
            if (now > nextBeat + period * 0.22f) {
                nextBeat += period.toLong(); firedNext = false
                confidence *= 0.93f
            }
        }
        // Bass pulse = the kick drum, plus a little of the bass level (so a bass line still moves things,
        // just gently). When the beat was shown ahead, the real hit arriving just after it tops up that
        // pulse as if it had started on time, instead of starting a second, late one.
        if (now - predictedAt < 160) kickHit *= kotlin.math.exp(-(now - predictedAt) / 120f)
        kickEnv = max(kickEnv * kotlin.math.exp(-dt / 0.12f), kickHit)
        bassRel = (1f + 0.45f * (bassEnergy - 1f) + 0.9f * kickEnv).coerceIn(0f, 4f)

        if (now - stAt > 5_000) {
            if (BuildConfig.DEBUG && stAt != 0L) android.util.Log.d(
                "VeilAudio",
                "bpm=${"%.1f".format(bpm)} conf=${"%.2f".format(confidence)} hits=$stHits onBeat=$stMatched " +
                    "offBeat=${stHits - stMatched} shownAhead=$stPredicted hitErr=${if (stMatched > 0) stErr / stMatched else 0}ms " +
                    "kick=${"%.2f".format(hitStrength)} snares=$stSnare hats=$stHat tone=${"%.2f".format(tone)} reads=$stReads",
            )
            stAt = now; stHits = 0; stMatched = 0; stPredicted = 0; stErr = 0; stReads = 0; stSnare = 0; stHat = 0
        }
        stReads++
    }

    private var prevPrevFlux = 0f; private var prevAt = 0L; private var prevKickHit = 0f
    // Tempo change that hasn't been confirmed yet (a new song, or the estimate wandering).
    private var pendingPeriod = 0f; private var pendingVotes = 0
    /** The tempo the onset statistics say (smoothed); the locked tempo may only fine-tune around it. */
    private var estPeriod = 0f

    private val kickHist = FloatArray(120)
    private val snareBand = BandHit(); private val hatBand = BandHit()
    private val prevLogHat = FloatArray(N / 2)
    /** log2 of each bin's frequency (for the brightness measure). */
    private val octave = FloatArray(N / 2)
    private var octaveFor = 0f
    private var toneMean = 0f
    private var kickEnv = 0f
    /** Typical kick strength on a beat, for beats shown ahead of time. */
    private var hitStrength = 0.6f
    private var predictedAt = 0L

    /** The predicted next beat has already been shown. */
    private var firedNext = false
    // Debug stats, logged every 5 s (logcat tag VeilAudio)
    private var stAt = 0L; private var stHits = 0; private var stMatched = 0; private var stPredicted = 0
    private var stErr = 0L; private var stReads = 0
    private var stSnare = 0; private var stHat = 0; private var snareUp = false; private var hatUp = false

    private fun onBeatEvidence(t: Long) {
        onsets.addLast(t)
        while (onsets.isNotEmpty() && t - onsets.first() > 8_000) onsets.removeFirst()
        estimateTempo()
        stHits++

        if (period > 0f && nextBeat > 0L) {
            val err = (t - nextBeat).toFloat()
            val window = period * 0.22f
            when {
                abs(err) <= window -> {                       // on the beat
                    stMatched++; stErr += abs(err).toLong()
                    // Not shown yet (not locked, or the hit came early): show it now.
                    if (!firedNext) lastBeatAt = t
                    // Pull the beat grid towards the hit: fully while locking on, half-way once locked, so
                    // one slightly early/late hit doesn't yank the rhythm around.
                    val locked = confidence > 0.5f
                    val gain = if (locked) 0.5f else 1f
                    // Hits that keep landing late mean the beat is a little slower than estimated (and the
                    // other way round): nudge the tempo, so it settles on e.g. 117 BPM instead of a nearby step.
                    // Only clean hits count, and it can't wander more than 3 % from the measured tempo (off-beat
                    // snares used to drag Blinding Lights from 171 down to 160 BPM).
                    if (locked && abs(err) < window * 0.5f && estPeriod > 0f) {
                        period = (period + err * 0.04f).coerceIn(estPeriod * 0.97f, estPeriod * 1.03f)
                        bpm = 60_000f / period
                    }
                    nextBeat += (err * gain).toLong() + period.toLong()
                    firedNext = false
                    confidence = min(1f, confidence + 0.15f)
                }
                t - lastBeatAt < period * 0.5f -> Unit        // a second hit on the beat just shown (flam)
                else -> {                                     // off-beat hit (fill, syncopation)
                    confidence = max(0f, confidence - 0.05f)
                    if (confidence < 0.35f) { lastBeatAt = t; nextBeat = t + period.toLong(); firedNext = false }
                }
            }
        } else {
            lastBeatAt = t
            if (period > 0f) { nextBeat = t + period.toLong(); firedNext = false }
        }
    }

    /** Tempo from inter-onset intervals: each interval (folded into 60-180 BPM) votes for a beat period. */
    private fun estimateTempo() {
        if (onsets.size < 6) return
        val votes = FloatArray(61) // 333..1000 ms in ~11 ms steps
        val list = onsets.toList()
        for (i in list.indices) for (j in i + 1 until min(list.size, i + 5)) {
            var d = (list[j] - list[i]).toFloat()
            if (d < 100f) continue
            while (d > 1000f) d /= 2f
            while (d < 333f) d *= 2f
            val idx = ((d - 333f) / 11f).toInt().coerceIn(0, 60)
            val wgt = 1f / (j - i) // neighbours count most
            votes[idx] += wgt
            if (idx > 0) votes[idx - 1] += wgt * 0.5f
            if (idx < 60) votes[idx + 1] += wgt * 0.5f
        }
        // Gentle preference for tempos around 120 BPM: when a song could be read at 86 or 172, the
        // evidence decides, this only breaks near-ties.
        for (i in 0..60) {
            val l = kotlin.math.log2((60_000f / (333f + i * 11f)) / 120f)
            votes[i] *= kotlin.math.exp(-0.5f * l * l / 0.8f)
        }
        var best = 0
        for (i in 1..60) if (votes[i] > votes[best]) best = i
        // Between bins, for a finer estimate than 11 ms steps.
        var off = 0f
        if (best in 1..59) {
            val l = votes[best - 1]; val c = votes[best]; val r = votes[best + 1]
            val den = l - 2f * c + r
            if (den < 0f) off = (0.5f * (l - r) / den).coerceIn(-0.5f, 0.5f)
        }
        val p = 333f + (best + off) * 11f
        if (period == 0f) { period = p; estPeriod = p; pendingVotes = 0 }
        else {
            val ratio = p / period
            val same = ratio in 0.95f..1.05f
            // Half, double, 2/3 or 3/2 of the current tempo is the same rhythm counted differently: switching
            // to it moves the beat grid and makes the pulses jump, so keep the tempo already locked on.
            val related = listOf(0.5f, 2f, 2f / 3f, 1.5f).any { abs(ratio / it - 1f) < 0.06f }
            when {
                same -> {
                    pendingVotes = 0
                    estPeriod = if (estPeriod == 0f) p else estPeriod * 0.8f + p * 0.2f
                    // While locked the hits fine-tune the tempo (above); this only steers while finding it.
                    if (confidence <= 0.5f) period = period * 0.8f + p * 0.2f
                }
                related && confidence > 0.3f -> pendingVotes = 0
                else -> {
                    // A different tempo has to win several estimates in a row (a new song) before it's used.
                    if (pendingPeriod > 0f && abs(p / pendingPeriod - 1f) < 0.05f) pendingVotes++
                    else { pendingPeriod = p; pendingVotes = 1 }
                    if (pendingVotes >= 4 || confidence < 0.2f) {
                        period = p; estPeriod = p; pendingVotes = 0
                        confidence = min(confidence, 0.3f)
                    }
                }
            }
        }
        bpm = 60_000f / period
    }

    companion object {
        private const val N = 1024
        /** How often the newest audio is read (ms). */
        private const val POLL_MS = 16L
        /** How far ahead a predicted beat is shown, to cover the delay to the screen (ms). */
        private const val LEAD_MS = 70L
    }
}

/**
 * Hit strength in one frequency band: how far this read's new energy (spectral flux) stands out from the
 * last ~2 s. 0 below 1.5 standard deviations (just the band being busy), ~1 at a clear hit.
 */
private class BandHit {
    private val hist = FloatArray(120)
    private var n = 0
    fun hit(x: Float): Float {
        hist[n % hist.size] = x; n++
        val c = min(n, hist.size)
        if (c < 30) return 0f
        var mean = 0f
        for (i in 0 until c) mean += hist[i]
        mean /= c
        var v = 0f
        for (i in 0 until c) { val d = hist[i] - mean; v += d * d }
        val sd = sqrt(v / c) + 1e-4f
        return (((x - mean) / sd - 1.5f) / 2.5f).coerceIn(0f, 1.5f)
    }
    fun reset() { hist.fill(0f); n = 0 }
}

/** One shared [AudioTap] for everyone who needs it (Ambient, screensaver, settings preview). */
object AudioHub {
    private var tap: AudioTap? = null
    private var refs = 0

    @Synchronized
    fun acquire(context: Context): AudioTap {
        val t = tap ?: AudioTap(context.applicationContext).also { tap = it }
        t.start()
        refs++
        return t
    }

    @Synchronized
    fun release() {
        refs--
        if (refs <= 0) { tap?.stop(); tap = null; refs = 0 }
    }
}
