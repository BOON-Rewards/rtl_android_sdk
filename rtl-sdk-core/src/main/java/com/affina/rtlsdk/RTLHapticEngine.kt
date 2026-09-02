package com.affina.rtlsdk

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.max
import kotlin.math.roundToInt

internal class RTLHapticEngine(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun capabilities(): Map<String, Any> {
        val supported = vibrator.hasVibrator()
        val hasAmplitudeControl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
        return mapOf(
            "supported" to supported,
            "protocolVersion" to RTLHapticPattern.PROTOCOL_VERSION,
            "impact" to supported,
            "continuous" to supported,
            "intensity" to (supported && hasAmplitudeControl),
            "sharpness" to false
        )
    }

    fun play(pattern: RTLHapticPattern) {
        if (!vibrator.hasVibrator() || pattern.events.isEmpty()) return
        val waveform = RTLHapticWaveform.create(pattern)
        if (waveform.timings.isEmpty()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = if (vibrator.hasAmplitudeControl()) {
                    waveform.amplitudes
                } else {
                    waveform.amplitudes.map { if (it == 0) 0 else VibrationEffect.DEFAULT_AMPLITUDE }.toIntArray()
                }
                vibrator.vibrate(VibrationEffect.createWaveform(waveform.timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(waveform.legacyTimings, -1)
            }
        } catch (error: RuntimeException) {
            println("[RTLSdk] Unable to play haptic pattern: ${error.message}")
        }
    }

    fun cancel() {
        vibrator.cancel()
    }
}

internal data class RTLHapticWaveform(
    val timings: LongArray,
    val amplitudes: IntArray,
    val legacyTimings: LongArray
) {
    companion object {
        private const val IMPACT_DURATION_MILLISECONDS = 30

        fun create(pattern: RTLHapticPattern): RTLHapticWaveform {
            val totalDuration = pattern.events.maxOfOrNull { event ->
                event.atMilliseconds + when (event.type) {
                    RTLHapticPattern.Event.Type.IMPACT -> IMPACT_DURATION_MILLISECONDS
                    RTLHapticPattern.Event.Type.CONTINUOUS -> event.durationMilliseconds ?: 0
                }
            } ?: 0
            if (totalDuration <= 0) return RTLHapticWaveform(longArrayOf(), intArrayOf(), longArrayOf())

            val samples = IntArray(totalDuration)
            pattern.events.forEach { event ->
                val duration = when (event.type) {
                    RTLHapticPattern.Event.Type.IMPACT -> IMPACT_DURATION_MILLISECONDS
                    RTLHapticPattern.Event.Type.CONTINUOUS -> event.durationMilliseconds ?: 0
                }
                val amplitude = (event.intensity * 255.0).roundToInt().coerceIn(0, 255)
                val end = (event.atMilliseconds + duration).coerceAtMost(samples.size)
                for (millisecond in event.atMilliseconds until end) {
                    samples[millisecond] = max(samples[millisecond], amplitude)
                }
            }

            val activeDuration = samples.indexOfLast { it > 0 } + 1
            if (activeDuration == 0) {
                return RTLHapticWaveform(longArrayOf(), intArrayOf(), longArrayOf())
            }

            val timings = mutableListOf<Long>()
            val amplitudes = mutableListOf<Int>()
            var start = 0
            while (start < activeDuration) {
                val amplitude = samples[start]
                var end = start + 1
                while (end < activeDuration && samples[end] == amplitude) end++
                timings += (end - start).toLong()
                amplitudes += amplitude
                start = end
            }

            val legacyTimings = mutableListOf<Long>()
            var legacyStart = 0
            while (legacyStart < activeDuration) {
                val vibrating = samples[legacyStart] > 0
                var legacyEnd = legacyStart + 1
                while (legacyEnd < activeDuration && (samples[legacyEnd] > 0) == vibrating) legacyEnd++
                if (legacyStart == 0 && vibrating) legacyTimings += 0L
                legacyTimings += (legacyEnd - legacyStart).toLong()
                legacyStart = legacyEnd
            }

            return RTLHapticWaveform(
                timings.toLongArray(),
                amplitudes.toIntArray(),
                legacyTimings.toLongArray()
            )
        }
    }
}
