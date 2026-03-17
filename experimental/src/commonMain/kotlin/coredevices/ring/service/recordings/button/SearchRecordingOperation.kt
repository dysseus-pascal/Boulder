package coredevices.ring.service.recordings.button

import coredevices.ring.agent.AgentFactory
import coredevices.ring.agent.ChatMode
import coredevices.ring.agent.McpSessionFactory
import coredevices.ring.database.room.repository.McpSandboxRepository
import org.koin.core.component.KoinComponent

class SearchRecordingOperation(
    agentFactory: AgentFactory,
    mcpSandboxRepository: McpSandboxRepository,
    mcpSessionFactory: McpSessionFactory,
    localRecordingId: Long,
    transferId: Long?,
    fileId: String,
) : DefaultRecordingOperation(
    mcpSandboxRepository = mcpSandboxRepository,
    mcpSessionFactory = mcpSessionFactory,
    chatAgent = agentFactory.createForChatMode(
        mode = ChatMode.Search
    ),
    recordingId = localRecordingId,
    transferId = transferId,
    fileId = fileId,
    forcedTool = null
)