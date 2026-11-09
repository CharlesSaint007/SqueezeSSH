package me.nick.squeezessh.ssh

import org.apache.sshd.agent.SshAgent
import org.apache.sshd.agent.SshAgentFactory
import org.apache.sshd.agent.SshAgentServer
import org.apache.sshd.agent.local.AgentImpl
import org.apache.sshd.agent.local.LocalAgentFactory
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.FactoryManager
import org.apache.sshd.common.channel.ChannelFactory
import org.apache.sshd.common.session.ConnectionService
import org.apache.sshd.common.session.Session
import java.security.KeyPair

/** A key offered to the remote host through agent forwarding, with the comment an agent lists it under. */
data class AgentKey(val keyPair: KeyPair, val comment: String)

/**
 * Answers the server's `auth-agent@openssh.com` channels with the keys attached to the session by [attach].
 *
 * MINA closes the agent when a forwarded channel ends, and every `ssh` run on the remote opens a new channel, so
 * each channel gets its own agent built from the session's keys instead of one shared instance. A session with no
 * keys attached gets an empty agent.
 */
class ForwardedAgentFactory : SshAgentFactory {
    override fun getChannelForwardingFactories(manager: FactoryManager): List<ChannelFactory> =
        LocalAgentFactory.DEFAULT_FORWARDING_CHANNELS

    override fun createClient(session: Session, manager: FactoryManager): SshAgent =
        AgentImpl().also { agent ->
            session.getAttribute(KEYS)?.forEach { agent.addIdentity(it.keyPair, it.comment) }
        }

    // The app only forwards its own agent to servers; it never accepts agent forwarding from one.
    override fun createServer(service: ConnectionService): SshAgentServer =
        throw UnsupportedOperationException("Serving an agent is not supported")

    companion object {
        private val KEYS = AttributeRepository.AttributeKey<List<AgentKey>>()

        /** Make [keys] available to the remote side of [session] once it requests agent forwarding. */
        fun attach(session: Session, keys: List<AgentKey>) {
            session.setAttribute(KEYS, keys)
        }
    }
}
