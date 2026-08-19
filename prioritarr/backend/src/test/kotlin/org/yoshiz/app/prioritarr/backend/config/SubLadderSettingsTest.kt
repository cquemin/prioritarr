package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SubLadderSettingsTest {

    @Test
    fun defaults_are_conservative_and_off() {
        val s = loadSettingsFrom(
            mapOf(
                "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
                "PRIORITARR_SONARR_API_KEY" to "k",
                "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
                "PRIORITARR_TAUTULLI_API_KEY" to "k",
                "PRIORITARR_QBIT_URL" to "http://vpn:8080",
                "PRIORITARR_SAB_URL" to "http://sabnzbd:8080",
                "PRIORITARR_SAB_API_KEY" to "k",
            ),
        )
        assertEquals(false, s.subLadderEnabled, "must ship disabled")
        assertEquals(false, s.subLadderWhisperEnabled, "whisper rung must ship disabled")
        assertEquals(2, s.subLadderWhisperMaxPriority, "P1/P2 by default")
        assertEquals(30, s.intervals.subLadderIntervalMinutes)
        assertEquals(5, s.intervals.subLadderMaxPerSweep, "start low; no Bazarr guard on this path")
    }

    @Test
    fun env_overrides_apply() {
        val s = loadSettingsFrom(
            mapOf(
                "PRIORITARR_SONARR_URL" to "http://sonarr:8989",
                "PRIORITARR_SONARR_API_KEY" to "k",
                "PRIORITARR_TAUTULLI_URL" to "http://tautulli:8181",
                "PRIORITARR_TAUTULLI_API_KEY" to "k",
                "PRIORITARR_QBIT_URL" to "http://vpn:8080",
                "PRIORITARR_SAB_URL" to "http://sabnzbd:8080",
                "PRIORITARR_SAB_API_KEY" to "k",
                "PRIORITARR_SUB_LADDER_ENABLED" to "true",
                "PRIORITARR_SUB_LADDER_WHISPER_ENABLED" to "true",
                "PRIORITARR_SUB_LADDER_WHISPER_MAX_PRIORITY" to "1",
            ),
        )
        assertEquals(true, s.subLadderEnabled)
        assertEquals(true, s.subLadderWhisperEnabled)
        assertEquals(1, s.subLadderWhisperMaxPriority)
    }
}
