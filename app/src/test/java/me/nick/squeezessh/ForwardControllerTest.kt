package me.nick.squeezessh

import kotlinx.coroutines.runBlocking
import me.nick.squeezessh.data.ForwardEntity
import me.nick.squeezessh.data.ForwardType
import me.nick.squeezessh.data.SnippetEntity
import me.nick.squeezessh.ssh.ForwardController
import me.nick.squeezessh.ssh.SnippetText
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import kotlin.concurrent.thread

/** Runs each forward type over a real SSH connection to an in-process MINA server on loopback. */
class ForwardControllerTest {
    private lateinit var server: SshServer
    private lateinit var client: SshClient
    private lateinit var session: ClientSession
    private lateinit var echo: ServerSocket

    @Before
    fun setUp() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(SecurityUtils.getKeyPairGenerator("EC").apply { initialize(256) }.generateKeyPair())
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, p, _ -> p == "pw" }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
        }
        client = SshClient.setUpDefaultClient().apply {
            // Don't pick up the developer's ~/.ssh keys or known_hosts; the test logs in by password only.
            keyIdentityProvider = org.apache.sshd.common.keyprovider.KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            hostConfigEntryResolver = org.apache.sshd.client.config.hosts.HostConfigEntryResolver.EMPTY
            serverKeyVerifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE
            // Same as the app's SshConnector: accept the server's forwarded-tcpip channels for -R.
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
        }
        session = client.connect("u", "127.0.0.1", server.port).verify(Duration.ofSeconds(10)).session.apply {
            addPasswordIdentity("pw")
            auth().verify(Duration.ofSeconds(10))
        }
        // The destination every forward points at: replies "echo:<line>".
        echo = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                val s = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { val line = it.getInputStream().bufferedReader().readLine(); it.getOutputStream().write("echo:$line\n".toByteArray()) } }
            }
        }
    }

    @After
    fun tearDown() {
        echo.close()
        session.close(true)
        client.stop()
        server.stop(true)
    }

    private fun roundTrip(port: Int, prelude: (Socket) -> Unit = {}): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5000
        prelude(s)
        s.getOutputStream().write("hi\n".toByteArray())
        s.getInputStream().bufferedReader().readLine()
    }

    @Test
    fun localForwardReachesDestination() = runBlocking {
        val f = ForwardEntity(hostId = "h", type = ForwardType.Local, bindPort = 0, destHost = "127.0.0.1", destPort = echo.localPort)
        val c = ForwardController(session, listOf(f))
        assertTrue(c.startAutomatic().isEmpty())
        val bound = c.states.value.single().bound
        assertNotNull(bound)
        assertEquals("echo:hi", roundTrip(bound!!.port))
        c.stop(f.id)
        assertNull(c.states.value.single().bound)
    }

    @Test
    fun remoteForwardReachesDestination() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val f = ForwardEntity(hostId = "h", type = ForwardType.Remote, bindPort = port, destHost = "127.0.0.1", destPort = echo.localPort)
        val c = ForwardController(session, listOf(f))
        c.startAutomatic()
        val bound = c.states.value.single().bound!!
        // The in-process server listens on the same machine, so its port is reachable here.
        assertEquals("echo:hi", roundTrip(bound.port))
    }

    @Test
    fun remoteForwardOnServerChosenPort() = runBlocking {
        val f = ForwardEntity(hostId = "h", type = ForwardType.Remote, bindPort = 0, destHost = "127.0.0.1", destPort = echo.localPort)
        val c = ForwardController(session, listOf(f))
        c.startAutomatic()
        val bound = c.states.value.single().bound!!
        assertTrue("server should report the port it picked", bound.port > 0)
        assertEquals("echo:hi", roundTrip(bound.port))
    }

    @Test
    fun dynamicForwardIsASocks5Proxy() = runBlocking {
        val f = ForwardEntity(hostId = "h", type = ForwardType.Dynamic, bindPort = 0)
        val c = ForwardController(session, listOf(f))
        c.startAutomatic()
        val reply = roundTrip(c.states.value.single().bound!!.port) { s ->
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(5, 1, 0)) // SOCKS5, one method: no auth
            assertEquals(5, inp.read()); assertEquals(0, inp.read())
            val p = echo.localPort
            out.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (p shr 8).toByte(), p.toByte())) // CONNECT 127.0.0.1:p
            val head = ByteArray(10).also { inp.readFully(it) }
            assertEquals("SOCKS reply code", 0, head[1].toInt())
        }
        assertEquals("echo:hi", reply)
    }

    @Test
    fun portInUseIsReportedWithoutThrowing() = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { taken ->
            val f = ForwardEntity(hostId = "h", type = ForwardType.Local, bindPort = taken.localPort, destHost = "127.0.0.1", destPort = 1)
            val failed = ForwardController(session, listOf(f)).startAutomatic()
            assertEquals(1, failed.size)
            assertNotNull(failed.single().error)
        }
    }

    @Test
    fun forwardsShowStoppedWhenTheConnectionCloses() = runBlocking {
        val f = ForwardEntity(hostId = "h", type = ForwardType.Dynamic, bindPort = 0)
        val c = ForwardController(session, listOf(f))
        c.startAutomatic()
        session.close(false).await(Duration.ofSeconds(5))
        assertNull(c.states.value.single().bound)
    }

    @Test
    fun snippetTextUsesEnterKeyLineEndings() {
        assertEquals("ls -la\r", SnippetText.of(SnippetEntity(name = "a", text = "ls -la")))
        assertEquals("a\rb", SnippetText.of(SnippetEntity(name = "a", text = "a\r\nb", sendEnter = false)))
        assertEquals("a\rb\r", SnippetText.of(SnippetEntity(name = "a", text = "a\nb\n")))
    }
}
