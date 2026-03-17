package coredevices.ring.service.recordings.button

import co.touchlab.kermit.Logger
import coredevices.indexai.agent.Agent
import coredevices.indexai.data.entity.RecordingEntryEntity
import coredevices.indexai.data.entity.RecordingEntryStatus
import coredevices.indexai.database.dao.RecordingEntryDao
import coredevices.mcp.data.ToolCallResult
import coredevices.ring.agent.McpSessionFactory
import coredevices.ring.database.room.repository.McpSandboxRepository
import coredevices.ring.database.room.repository.RingTransferRepository
import coredevices.ring.service.recordings.RecordingProcessingQueue
import coredevices.ring.service.recordings.RecordingProcessingStage
import coredevices.ring.service.recordings.RecordingProcessor
import coredevices.ring.storage.RecordingStorage
import coredevices.util.queue.RecoverableTaskException
import coredevices.util.transcription.TranscriptionException
import coredevices.util.transcription.TranscriptionSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

interface RecordingOperation {
    suspend fun run(handle: RecordingProcessingQueue.TaskHandle? = null)
}

open class DefaultRecordingOperation(
    private val mcpSandboxRepository: McpSandboxRepository,
    private val mcpSessionFactory: McpSessionFactory,
    private val chatAgent: Agent,
    private val recordingId: Long,
    private val transferId: Long?,
    private val fileId: String,
    private val forcedTool: (suspend (messageText: String) -> ToolCallResult)?
) : RecordingOperation, KoinComponent {
    companion object {
        private val logger = Logger.withTag("DefaultRecordingOperation")
    }

    private val recordingStorage: RecordingStorage by inject()
    private val recordingEntryDao: RecordingEntryDao by inject()
    private val recordingProcessor: RecordingProcessor by inject()
    private val ringTransferRepository: RingTransferRepository by inject()

    override suspend fun run(handle: RecordingProcessingQueue.TaskHandle?) {
        val entryId = withContext(Dispatchers.IO) {
            if (handle?.stage is RecordingProcessingStage.RecordingEntryCreated) {
                (handle.stage as RecordingProcessingStage.RecordingEntryCreated).recordingEntryId
            } else {
                val newId = recordingEntryDao.insertRecordingEntry(
                    RecordingEntryEntity(
                        recordingId = recordingId,
                        fileName = fileId
                    )
                )
                handle?.updateStage(RecordingProcessingStage.RecordingEntryCreated(
                    recordingEntryId = newId,
                    previous = handle.stage!! as RecordingProcessingStage.RecordingEntityCreated)
                )
                newId
            }
        }
        transferId?.let {
            ringTransferRepository.linkRecordingEntryToTransfer(
                transferId,
                entryId
            )
        }
        val (source, meta) = recordingStorage.openRecordingSource(fileId)
        coroutineScope {
            val mcpSession = mcpSessionFactory.createForSandboxGroup(
                mcpSandboxRepository.getDefaultGroupId(),
                this
            )
            val transcription = try {
                recordingProcessor.transcribe(
                    audioSource = source,
                    sampleRate = meta.cachedMetadata.sampleRate,
                ).flowOn(Dispatchers.IO)
                    .first { it is TranscriptionSessionStatus.Transcription } as TranscriptionSessionStatus.Transcription
            } catch (e: TranscriptionException.TranscriptionNetworkError) {
                recordingEntryDao.updateRecordingEntryStatus(
                    entryId,
                    status = RecordingEntryStatus.transcription_error,
                    error = "Network error during transcription: ${e.message}"
                )
                recordingEntryDao.updateRecordingEntryTranscription(
                    entryId,
                    transcription = null,
                    modelUsed = e.modelUsed
                )
                throw RecoverableTaskException("Network error during transcription", e)
            } catch (e: Exception) {
                recordingEntryDao.updateRecordingEntryStatus(
                    entryId,
                    status = RecordingEntryStatus.transcription_error,
                    error = e.message
                )
                if (e is TranscriptionException) {
                    recordingEntryDao.updateRecordingEntryTranscription(
                        entryId,
                        transcription = null,
                        modelUsed = e.modelUsed
                    )
                }
                throw e
            } finally {
                source.close()
            }
            recordingEntryDao.updateRecordingEntryTranscription(
                entryId,
                transcription.text,
                transcription.modelUsed
            )

            try {
                mcpSession.openSession()
                logger.d { "Agent running..." }
                recordingEntryDao.updateRecordingEntryStatus(
                    entryId,
                    status = RecordingEntryStatus.agent_processing
                )
                recordingProcessor.processText(
                    recordingId = recordingId,
                    recordingEntryId = entryId,
                    mcpSession = mcpSession,
                    agent = chatAgent,
                    forcedTool = forcedTool?.let { { it(transcription.text) } },
                    text = transcription.text
                )
                logger.d { "Processing complete." }
                recordingEntryDao.updateRecordingEntryStatus(
                    entryId,
                    status = RecordingEntryStatus.completed
                )
            } catch (e: Exception) {
                recordingEntryDao.updateRecordingEntryStatus(
                    entryId,
                    status = RecordingEntryStatus.agent_error,
                    error = e.message
                )
                throw e
            } finally {
                withTimeout(3.seconds) {
                    mcpSession.closeSession()
                }
            }
        }
    }
}