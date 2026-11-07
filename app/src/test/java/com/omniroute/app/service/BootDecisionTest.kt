package com.omniroute.app.service

import com.omniroute.app.config.AppMeta
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootDecisionTest {
    @Test fun boot_starts_when_enabled() {
        assertTrue(BootDecision.shouldStart(AppMeta(bootStart = true)))
    }

    @Test fun boot_skipped_when_disabled() {
        assertFalse(BootDecision.shouldStart(AppMeta(bootStart = false)))
    }
}
