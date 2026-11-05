package me.nick.squeezessh

import me.nick.squeezessh.security.AuthRules
import me.nick.squeezessh.security.Masking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class MaskingTest {
    @Test
    fun theExampleFromTheSpec() {
        assertEquals("192...3", Masking.maskAddress("192.168.3.3"))
        assertEquals("pi@192...3", Masking.maskedTarget("pi", "192.168.3.3"))
    }

    @Test
    fun hostnamesAndIpv6() {
        assertEquals("exa...m", Masking.maskAddress("example.com"))
        assertEquals("fe8...1", Masking.maskAddress("fe80::1"))
    }

    @Test
    fun shortNamesAreNotShownWhole() {
        assertEquals("p...", Masking.maskAddress("pi5"))
        assertEquals("p...", Masking.maskAddress("pi5x"))
        assertEquals("pi5...x", Masking.maskAddress("pi5ex"))
    }

    @Test
    fun noUserJustShowsTheMaskedAddress() {
        assertEquals("192...3", Masking.maskedTarget(null, "192.168.3.3"))
        assertEquals("192...3", Masking.maskedTarget("", "192.168.3.3"))
        assertEquals("", Masking.maskAddress("  "))
    }

    @Test
    fun portIsNeverPartOfTheMaskedText() {
        assertFalse(Masking.maskedTarget("pi", "192.168.3.3").contains(":"))
    }

    @Test
    fun authIsOnlyRequiredWhenProtectedAndThePhoneCanPrompt() {
        assertTrue(AuthRules.requiresAuth(enabled = true, canAuthenticate = true))
        assertFalse(AuthRules.requiresAuth(enabled = false, canAuthenticate = true))
        // No screen lock left: nothing can be proven, so the owner is never trapped.
        assertFalse(AuthRules.requiresAuth(enabled = true, canAuthenticate = false))
    }

    @Test
    fun disablingChecksTheSavedValueNotTheDraft() {
        assertTrue(AuthRules.requiresAuthToDisable(savedEnabled = true, canAuthenticate = true))
        assertFalse(AuthRules.requiresAuthToDisable(savedEnabled = false, canAuthenticate = true))
    }

    private data class Hop(val name: String, val isProtected: Boolean)

    @Test
    fun everyProtectedHopIsAskedAboutInOrderIncludingJumpHosts() = runBlocking {
        val asked = mutableListOf<String>()
        val chain = listOf(Hop("jump", true), Hop("middle", false), Hop("target", true))
        val refusal = AuthRules.firstRefusal(chain, { it.isProtected }) { asked += it.name; null }
        assertEquals(null, refusal)
        // The protected jump host is not a way around its own requirement; the unprotected one isn't asked.
        assertEquals(listOf("jump", "target"), asked)
    }

    @Test
    fun aRefusalStopsTheChainAndIsReported() = runBlocking {
        val asked = mutableListOf<String>()
        val chain = listOf(Hop("jump", true), Hop("target", true))
        val refusal = AuthRules.firstRefusal(chain, { it.isProtected }) { asked += it.name; "cancelled" }
        assertEquals("cancelled", refusal)
        assertEquals(listOf("jump"), asked)
    }
}
