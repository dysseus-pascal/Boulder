package coredevices.ring.agent

import co.touchlab.kermit.Logger
import coredevices.indexai.data.entity.ConversationMessageDocument
import coredevices.ring.api.NenyaClient
import coredevices.ring.api.NenyaModel

/**
 * Online agent backed by the Nenya HTTP API for MCP sandbox mode: a generic
 * tool-using prompt, with the model chosen from the sandbox group's model type.
 */
class McpSandboxAgentNenya(
    nenyaClient: NenyaClient,
    model: NenyaModel,
    conversation: List<ConversationMessageDocument>,
): AgentNenya(nenyaClient, AGENT_CONTEXT, model, conversation) {
    override val label = "Nenya"

    override val logger: Logger = Logger.withTag("McpSandboxAgentNenya")
    companion object {
        private const val AGENT_CONTEXT = """
You are a helpful assistant fulfilling user requests with the tools available to you.
## Response and action guidelines:
 - Keep responses concise; they may be shown on a small display.
 - Avoid additional commentary after taking a final action unless the user asked for it. The user can see actions without you notifying them.
"""
    }
}
