package me.nick.squeezessh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.nick.squeezessh.crypto.KeyAlgorithm
import me.nick.squeezessh.crypto.KeyCodec
import me.nick.squeezessh.data.KnownHostDao
import me.nick.squeezessh.data.KnownHostEntity
import me.nick.squeezessh.ssh.HostKeyCheck
import me.nick.squeezessh.ssh.HostKeyVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trust-on-first-use behaviour, including what "Clear known hosts" means for the next connection. */
class HostKeyVerifierTest {
    /** An in-memory stand-in for the Room DAO. */
    private class FakeDao : KnownHostDao {
        val rows = MutableStateFlow(emptyList<KnownHostEntity>())
        override suspend fun forHost(hostPort: String) = rows.value.filter { it.hostPort == hostPort }
        override suspend fun upsert(entry: KnownHostEntity) {
            rows.value = rows.value.filterNot { it.hostPort == entry.hostPort && it.keyType == entry.keyType } + entry
        }
        override suspend fun forget(hostPort: String) { rows.value = rows.value.filterNot { it.hostPort == hostPort } }
        override suspend fun getAll() = rows.value
        override fun observeHostCount(): Flow<Int> = MutableStateFlow(rows.value.map { it.hostPort }.distinct().size)
        override suspend fun upsertAll(entries: List<KnownHostEntity>) = entries.forEach { upsert(it) }
        override suspend fun deleteAll() { rows.value = emptyList() }
    }

    private val key = KeyCodec.generate(KeyAlgorithm.Ed25519, "a").keyPair.public
    private val otherKey = KeyCodec.generate(KeyAlgorithm.Ed25519, "b").keyPair.public

    @Test
    fun acceptedKeyIsTrustedUntilKnownHostsAreCleared() = runBlocking {
        val dao = FakeDao()
        val verifier = HostKeyVerifier(dao)

        val first = verifier.check("example.com", 22, key)
        assertTrue(first is HostKeyCheck.Unknown)
        verifier.remember(first, key)
        assertTrue(verifier.check("example.com", 22, key) is HostKeyCheck.Trusted)

        dao.deleteAll()

        // Cleared: the same key is "unknown" again, so the user is asked to accept it, not shown a warning.
        assertTrue(verifier.check("example.com", 22, key) is HostKeyCheck.Unknown)
    }

    @Test
    fun aDifferentKeyOnAKnownHostIsFlaggedAsChanged() = runBlocking {
        val verifier = HostKeyVerifier(FakeDao())
        verifier.remember(verifier.check("example.com", 2222, key), key)
        val check = verifier.check("example.com", 2222, otherKey)
        assertTrue(check is HostKeyCheck.Changed)
        assertEquals("[example.com]:2222", check.hostPort)
    }
}
