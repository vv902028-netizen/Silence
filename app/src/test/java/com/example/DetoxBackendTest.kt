package com.example

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.detox.data.DetoxMode
import com.example.detox.data.DetoxSessionRepository
import com.example.detox.ui.TimeFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DetoxBackendTest {

    private lateinit var context: Context
    private lateinit var repository: DetoxSessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = DetoxSessionRepository.getInstance(context)
        repository.completeSession()
        repository.clearCompletedSummary()
        repository.setBlockNotificationShade(false)
    }

    @After
    fun tearDown() {
        repository.completeSession()
        repository.clearCompletedSummary()
    }

    @Test
    fun `monotonic countdown ignores system time changes and tracks elapsedRealtime`() {
        repository.startSession(durationMinutes = 30, mode = DetoxMode.PHONE_ONLY)
        assertTrue(repository.isSessionActive())

        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        val remainingBeforeTamper = repository.computeRemainingMs()
        assertEquals(25L * 60_000L, remainingBeforeTamper)

        // Simulate user jumping wall clock forward; monotonic remaining must remain unchanged
        val afterTamper = repository.onSystemTimeChanged()
        assertTrue(afterTamper.timeTamperDetected)
        assertEquals(remainingBeforeTamper, repository.computeRemainingMs())
    }

    @Test
    fun `revocation and shade toggle are strictly blocked during active session and allowed when idle`() {
        repository.startSession(durationMinutes = 15, mode = DetoxMode.TOTAL_VACUUM)

        assertFalse(repository.disableDeviceAdminIfIdle())
        assertFalse(repository.disableAccessibilityIfIdle())
        assertFalse(repository.revokeAllProtectionIfIdle())

        repository.setBlockNotificationShade(true)
        assertFalse(repository.readBlockNotificationShade())

        val summary = repository.completeSession()
        assertNotNull(summary)
        assertEquals(15L * 60_000L, summary?.durationMs)
        assertEquals(DetoxMode.TOTAL_VACUUM, summary?.mode)

        // Idempotent second completion call must return null
        assertNull(repository.completeSession())
        assertFalse(repository.isSessionActive())

        // Now idle: settings and revocation paths succeed cleanly
        repository.setBlockNotificationShade(true)
        assertTrue(repository.readBlockNotificationShade())
        assertTrue(repository.revokeAllProtectionIfIdle())
    }

    @Test
    fun `session expires automatically once monotonic duration elapses`() {
        repository.startSession(durationMinutes = 5, mode = DetoxMode.PHONE_ONLY)
        repository.recordBypassAttempt()
        repository.recordBypassAttempt()

        ShadowSystemClock.advanceBy(Duration.ofMinutes(5).plusSeconds(1))
        assertEquals(0L, repository.computeRemainingMs())
        assertFalse(repository.isSessionActive())

        val summary = repository.completeSession()
        assertNotNull(summary)
        assertEquals(2, summary?.bypassAttempts)
    }

    @Test
    fun `package allowlist enforces mode rules and blocks settings and browsers`() {
        // Always allowed
        assertTrue(repository.isAllowedForegroundPackage(context.packageName, DetoxMode.PHONE_ONLY))
        assertTrue(repository.isAllowedForegroundPackage(context.packageName, DetoxMode.TOTAL_VACUUM))
        assertTrue(repository.isAllowedForegroundPackage("com.android.emergency", DetoxMode.TOTAL_VACUUM))

        // Dialer allowed only in PHONE_ONLY
        assertTrue(repository.isAllowedForegroundPackage("com.google.android.dialer", DetoxMode.PHONE_ONLY))
        assertFalse(repository.isAllowedForegroundPackage("com.google.android.dialer", DetoxMode.TOTAL_VACUUM))

        // Settings, Play Store, browsers, social apps blocked in both modes
        listOf(
            "com.android.settings",
            "com.android.chrome",
            "org.telegram.messenger",
            "com.android.vending",
            "com.miui.securitycenter"
        ).forEach { blocked ->
            assertFalse("Expected $blocked blocked in PHONE_ONLY", repository.isAllowedForegroundPackage(blocked, DetoxMode.PHONE_ONLY))
            assertFalse("Expected $blocked blocked in TOTAL_VACUUM", repository.isAllowedForegroundPackage(blocked, DetoxMode.TOTAL_VACUUM))
        }
    }

    @Test
    fun `time formatter formats durations and countdowns accurately including multi-day`() {
        assertEquals("00:05", TimeFormatter.formatSetupDuration(5))
        assertEquals("01:30", TimeFormatter.formatSetupDuration(90))
        assertEquals("12:00", TimeFormatter.formatSetupDuration(720))
        assertEquals("1 д 00:00", TimeFormatter.formatSetupDuration(1440))
        assertEquals("7 д 00:00", TimeFormatter.formatSetupDuration(10080))

        assertEquals("00:00", TimeFormatter.formatCountdown(0L))
        assertEquals("00:59", TimeFormatter.formatCountdown(59_000L))
        assertEquals("01:00:00", TimeFormatter.formatCountdown(3_600_000L))
        assertEquals("2 д 01:00:00", TimeFormatter.formatCountdown(176_400_000L))
        assertTrue(SystemClock.elapsedRealtime() >= 0L)
    }

    @Test
    fun `seven day session and granular panel toggles are enforced properly`() {
        repository.setBlockControlCenter(false)

        val snap = repository.startSession(durationMinutes = 7 * 24 * 60, mode = DetoxMode.TOTAL_VACUUM)
        assertEquals(7L * 24L * 60L * 60_000L, snap.totalDurationMs)

        // Toggles must ignore changes during an active session
        repository.setBlockControlCenter(true)
        assertFalse(repository.readBlockControlCenter())

        repository.completeSession()

        // Once idle, toggles save cleanly
        repository.setBlockControlCenter(true)
        assertTrue(repository.readBlockControlCenter())
    }
}
