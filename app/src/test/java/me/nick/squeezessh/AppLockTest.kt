package me.nick.squeezessh

import me.nick.squeezessh.security.AppLock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    @Test
    fun staysOpenForAShortTripAway() {
        assertFalse(AppLock.shouldRelock(backgroundedAt = 1_000, now = 1_000 + 5_000))
        assertFalse(AppLock.shouldRelock(backgroundedAt = 1_000, now = 1_000 + AppLock.GRACE_MS))
    }

    @Test
    fun locksAgainAfterTheGracePeriod() {
        assertTrue(AppLock.shouldRelock(backgroundedAt = 1_000, now = 1_000 + AppLock.GRACE_MS + 1))
    }

    @Test
    fun nothingToRelockIfTheAppWasNeverBackgrounded() {
        assertFalse(AppLock.shouldRelock(backgroundedAt = null, now = 999_999))
    }
}
