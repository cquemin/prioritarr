package org.yoshiz.app.prioritarr.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchControlSettingsTest {
    private fun base() = Settings(
        sonarrUrl = "x", sonarrApiKey = "x", tautulliUrl = "x", tautulliApiKey = "x",
        qbitUrl = "x", sabUrl = "x", sabApiKey = "x",
    )

    @Test fun defaults() {
        val s = base()
        assertEquals(3, s.intervals.searchCongestionThreshold)
        assertEquals(true, s.cancelBackfillForPriority)
    }

    @Test fun override_applies() {
        val merged = applySettingsOverride(
            base(),
            EditableSettings(searchCongestionThreshold = 5, cancelBackfillForPriority = false),
        )
        assertEquals(5, merged.intervals.searchCongestionThreshold)
        assertEquals(false, merged.cancelBackfillForPriority)
    }
}
