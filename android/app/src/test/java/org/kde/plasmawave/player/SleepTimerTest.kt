package org.kde.plasmawave.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    @Test fun formatsMinutesSecondsAndHours() {
        assertEquals("00:00", formatSleepTimerRemaining(0L))
        assertEquals("00:00", formatSleepTimerRemaining(-500L))
        assertEquals("00:01", formatSleepTimerRemaining(1L))
        assertEquals("15:00", formatSleepTimerRemaining(15 * 60_000L))
        assertEquals("14:59", formatSleepTimerRemaining(15 * 60_000L - 1_500L))
        assertEquals("1:00:00", formatSleepTimerRemaining(60 * 60_000L))
    }

    @Test fun summaryLabelAndActiveReflectState() {
        assertFalse(SleepTimerState.Off.isActive())
        assertNull(SleepTimerState.Off.summaryLabel())

        val countdown = SleepTimerState.Countdown(endElapsedMs = 90_000L, remainingMs = 90_000L)
        assertTrue(countdown.isActive())
        assertEquals("01:30", countdown.summaryLabel())

        assertTrue(SleepTimerState.EndOfTrack.isActive())
        assertEquals("Şarkı bitince", SleepTimerState.EndOfTrack.summaryLabel())
    }
}
