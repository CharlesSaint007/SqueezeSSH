package me.nick.squeezessh.terminal

import kotlinx.coroutines.delay

/** How a held key-bar key repeats; [enabled] off means a held key types once. */
data class KeyRepeatConfig(val enabled: Boolean, val delayMs: Int, val perSecond: Int) {
    val intervalMs: Long get() = 1000L / perSecond.coerceAtLeast(1)
}

/**
 * Types a held key like a hardware keyboard: once straight away, then after the configured delay again every
 * interval until the coroutine is cancelled (the finger lifts).
 */
suspend fun repeatKey(config: KeyRepeatConfig, fire: () -> Unit) {
    fire()
    if (!config.enabled) return
    delay(config.delayMs.toLong())
    while (true) {
        fire()
        delay(config.intervalMs)
    }
}
