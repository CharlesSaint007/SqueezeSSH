package me.nick.squeezessh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.nick.squeezessh.terminal.KeyRepeatConfig
import me.nick.squeezessh.terminal.repeatKey
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyRepeaterTest {
    private val config = KeyRepeatConfig(enabled = true, delayMs = 400, perSecond = 20) // every 50 ms

    @Test
    fun `types once, waits the delay, then repeats until cancelled`() = runTest {
        var count = 0
        val job = launch { repeatKey(config) { count++ } }
        runCurrent()
        assertEquals(1, count)
        advanceTimeBy(399)
        assertEquals(1, count)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, count)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(4, count)
        job.cancel()
        advanceTimeBy(1000)
        assertEquals(4, count)
    }

    @Test
    fun `a quick tap types exactly once`() = runTest {
        var count = 0
        val job = launch { repeatKey(config) { count++ } }
        runCurrent()
        advanceTimeBy(300)
        job.cancel()
        advanceTimeBy(1000)
        assertEquals(1, count)
    }

    @Test
    fun `with repeat off a held key types once`() = runTest {
        var count = 0
        launch { repeatKey(config.copy(enabled = false)) { count++ } }
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(1, count)
    }
}
