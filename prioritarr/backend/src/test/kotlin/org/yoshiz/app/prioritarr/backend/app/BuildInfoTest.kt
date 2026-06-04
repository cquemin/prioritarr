package org.yoshiz.app.prioritarr.backend.app

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test fun fields_are_non_blank() {
        assertTrue(BuildInfo.version.isNotBlank(), "version blank")
        assertTrue(BuildInfo.gitSha.isNotBlank(), "gitSha blank")
        assertTrue(BuildInfo.buildTime.isNotBlank(), "buildTime blank")
    }
}
