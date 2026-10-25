package dev.briiqn.verbalize.domain.speech

import dev.briiqn.verbalize.domain.model.Player

/**
 * Adaptive voice-activity detection: tracks a per-player noise floor so held
 * keys or room hum never read as speech, while bursts above the adaptive
 * threshold stamp lastSpeechTick.
 */
object SpeechGate {
    const val SPEECH_RECENT_TICKS = 80L

    fun observe(player: Player, energyByte: Int, serverTick: Long) {
        val e = energyByte.toDouble()
        if (e < player.noiseFloor) {
            player.noiseFloor += (e - player.noiseFloor) * 0.05
        } else {
            player.noiseFloor += (e - player.noiseFloor) * 0.01
        }
        val dev = kotlin.math.abs(e - player.noiseFloor)
        player.noiseDev += (dev - player.noiseDev) * 0.05
        val threshold = maxOf(player.noiseFloor + 4 * player.noiseDev, player.noiseFloor * 2.5, 10.0)
        if (e > threshold) player.lastSpeechTick = serverTick
    }

    /** A non-empty transcript is definitive proof of speech. */
    fun allowsInteract(player: Player, transcript: String, serverTick: Long): Boolean =
        transcript.isNotBlank() || serverTick - player.lastSpeechTick <= SPEECH_RECENT_TICKS
}
