package me.nick.squeezessh

import me.nick.squeezessh.crypto.KeyAlgorithm
import me.nick.squeezessh.crypto.KeyCodec
import me.nick.squeezessh.ssh.AgentKey
import me.nick.squeezessh.ssh.ForwardedAgentFactory
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

/** The agent the app offers a server when a host has agent forwarding on. */
class ForwardedAgentTest {
    private lateinit var server: SshServer
    private lateinit var client: SshClient
    private lateinit var session: ClientSession
    private val factory = ForwardedAgentFactory()

    @Before
    fun setUp() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(SecurityUtils.getKeyPairGenerator("EC").apply { initialize(256) }.generateKeyPair())
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, p, _ -> p == "pw" }
            start()
        }
        client = SshClient.setUpDefaultClient().apply {
            keyIdentityProvider = org.apache.sshd.common.keyprovider.KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            hostConfigEntryResolver = org.apache.sshd.client.config.hosts.HostConfigEntryResolver.EMPTY
            serverKeyVerifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE
            agentFactory = factory
            start()
        }
        session = client.connect("u", "127.0.0.1", server.port).verify(Duration.ofSeconds(10)).session
    }

    @After
    fun tearDown() {
        session.close(true)
        client.stop()
        server.stop(true)
    }

    private fun key(name: String) = KeyCodec.generate(KeyAlgorithm.Ed25519, name).keyPair to name

    @Test
    fun agentListsTheAttachedKeysWithTheirNames() {
        val (a, nameA) = key("laptop")
        val (b, nameB) = key("vault")
        ForwardedAgentFactory.attach(session, listOf(AgentKey(a, nameA), AgentKey(b, nameB)))

        val listed = factory.createClient(session, client).identities.map { it.key to it.value }

        assertEquals(listOf(a.public to nameA, b.public to nameB), listed)
    }

    @Test
    fun agentSignsWithTheAttachedKey() {
        val (pair, name) = key("signer")
        ForwardedAgentFactory.attach(session, listOf(AgentKey(pair, name)))
        val data = "challenge".toByteArray()

        val (algorithm, signature) = factory.createClient(session, client).sign(session, pair.public, null, data)

        assertEquals("ssh-ed25519", algorithm)
        assertTrue(signature.isNotEmpty())
    }

    @Test
    fun sessionWithNoKeysGetsAnEmptyAgent() {
        assertTrue(factory.createClient(session, client).identities.none())
    }

    @Test
    fun everyChannelGetsItsOwnAgent() {
        // MINA closes the agent when its channel ends; a shared one would be dead for the next `ssh` run.
        val (pair, name) = key("shared")
        ForwardedAgentFactory.attach(session, listOf(AgentKey(pair, name)))

        val first = factory.createClient(session, client)
        first.close()
        val second = factory.createClient(session, client)

        assertNotSame(first, second)
        assertTrue(second.isOpen)
        assertEquals(1, second.identities.count())
    }
}
