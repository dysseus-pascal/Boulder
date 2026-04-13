package coredevices.ring.service.recordings

import co.touchlab.kermit.Logger
import coredevices.indexai.data.entity.RecordingDocument
import coredevices.indexai.data.entity.RecordingEntry
import coredevices.indexai.data.entity.RecordingEntryStatus
import coredevices.indexai.database.dao.ConversationMessageDao
import coredevices.indexai.database.dao.RecordingEntryDao
import coredevices.indexai.util.JsonSnake
import coredevices.ring.database.Preferences
import coredevices.ring.encryption.DocumentEncryptor
import coredevices.mcp.data.ToolCallResult
import coredevices.ring.database.firestore.dao.FirestoreRecordingsDao
import coredevices.ring.database.firestore.dao.FirestoreTracesDao
import coredevices.ring.util.trace.TraceSessionExporter
import coredevices.ring.agent.builtin_servlets.notes.CreateNoteTool
import coredevices.ring.data.ProcessingTask
import coredevices.ring.data.RecordingProcessingTask
import coredevices.ring.data.entity.room.TraceEventData
import coredevices.ring.database.room.repository.RecordingProcessingTaskRepository
import coredevices.ring.database.room.repository.RecordingRepository
import coredevices.ring.database.room.repository.RingTransferRepository
import coredevices.ring.service.RecordingBackgroundScope
import coredevices.ring.service.parseAsButtonSequence
import coredevices.ring.service.recordings.button.RecordingOperationFactory
import coredevices.ring.storage.RecordingStorage
import coredevices.ring.util.trace.RingTraceSession
import coredevices.util.queue.PersistentQueueScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RecordingProcessingQueue(
    private val recordingStorage: RecordingStorage,
    private val transferRepository: RingTransferRepository,
    private val recordingRepository: RecordingRepository,
    private val queueTaskRepository: RecordingProcessingTaskRepository,
    private val recordingOperationFactory: RecordingOperationFactory,
    private val scope: RecordingBackgroundScope,
    private val recordingPreprocessor: RecordingPreprocessor,
    private val trace: RingTraceSession,
    rescheduleDelay: Duration = 1.minutes,
    maxConcurrency: Int = 20,
): KoinComponent, PersistentQueueScheduler<RecordingProcessingTask>(
    repository = queueTaskRepository,
    scope = scope,
    label = "RecordingProcessing",
    rescheduleDelay = rescheduleDelay,
    maxConcurrency = maxConcurrency,
) {
    companion object {
        private val logger = Logger.withTag("RecordingProcessingQueue")
    }

    private val uploadingIds = mutableSetOf<Long>()

    init {
        // Observe local recordings and sync to Firestore. Mirrors the old RingService
        // logic: on every emission, for each LocalRecording either (a) upload if it has
        // no firestoreId, or (b) fetch the remote doc and compare `updated` timestamps,
        // re-uploading when the local copy is newer. This catches incremental updates
        // (entries/messages added after the initial row was created), which the
        // firestoreId-only filter silently dropped.
        val preferences: Preferences = get()
        recordingRepository.getAllRecordings().drop(1).debounce(300).onEach { recordings ->
            if (!preferences.backupEnabled.value) return@onEach
            val firestoreRecordingsDao: FirestoreRecordingsDao = get()
            val recordingEntryDao: RecordingEntryDao = get()
            val conversationMessageDao: ConversationMessageDao = get()

            val uploadOrUpdate = recordings.filter { localRecording ->
                if (localRecording.id in uploadingIds) return@filter false
                val firestoreId = localRecording.firestoreId ?: return@filter true
                try {
                    val remoteRecording = firestoreRecordingsDao.getRecording(firestoreId)
                        .get().data<RecordingDocument>()
                    remoteRecording.updated < localRecording.updated.toEpochMilliseconds()
                } catch (e: Exception) {
                    logger.e(e) { "Error fetching remote recording $firestoreId, will attempt to re-upload" }
                    true
                }
            }
            if (uploadOrUpdate.isEmpty()) return@onEach
            logger.i { "Found ${uploadOrUpdate.size} local recordings to upload or update" }

            for (localRecording in uploadOrUpdate) {
                if (!uploadingIds.add(localRecording.id)) continue // already in-flight
                scope.launch(Dispatchers.IO) {
                    try {
                        val entries = recordingEntryDao.getEntriesForRecording(localRecording.id).first()
                        val messages = conversationMessageDao.getMessagesForRecording(localRecording.id).first()
                        var doc = localRecording.toDocument(
                            entries = entries.map {
                                RecordingEntry(
                                    timestamp = it.timestamp,
                                    fileName = it.fileName,
                                    status = it.status,
                                    transcription = it.transcription,
                                    transcribedUsingModel = it.transcribedUsingModel,
                                    error = it.error,
                                    ringTransferInfo = it.ringTransferInfo,
                                    userMessageId = it.userMessageId
                                )
                            },
                            messages = messages.map { it.document },
                            metadata = null
                        )
                        if (preferences.useEncryption.value) {
                            val encryptor: DocumentEncryptor = get()
                            val key = encryptor.getKey()
                            if (key != null) {
                                doc = encryptor.encryptDocument(doc, key)
                                logger.i { "Encrypted recording ${localRecording.id} before upload" }
                            } else {
                                logger.w { "Encryption enabled but no key available — uploading unencrypted" }
                            }
                        }
                        val existingFirestoreId = localRecording.firestoreId
                        val firestoreRecordingId = if (existingFirestoreId == null) {
                            val remoteId = firestoreRecordingsDao.addRecording(doc).id
                            recordingRepository.updateRecordingFirestoreId(localRecording.id, remoteId)
                            logger.i { "Uploaded recording ${localRecording.id} → $remoteId" }
                            remoteId
                        } else {
                            firestoreRecordingsDao.setRecording(existingFirestoreId, doc)
                            logger.i { "Updated recording ${localRecording.id} → $existingFirestoreId" }
                            existingFirestoreId
                        }
                        val isFinal = entries.isNotEmpty() && entries.all {
                            it.status == RecordingEntryStatus.completed || it.status.isError()
                        }
                        if (isFinal) {
                            try {
                                val exporter: TraceSessionExporter = get()
                                val sessions = exporter.exportForRecording(localRecording.id)
                                if (sessions.isNotEmpty()) {
                                    val firestoreTracesDao: FirestoreTracesDao = get()
                                    firestoreTracesDao.setTrace(firestoreRecordingId, sessions)
                                    logger.i { "Uploaded trace (${sessions.size} sessions) for recording ${localRecording.id} → $firestoreRecordingId" }
                                }
                            } catch (e: Exception) {
                                logger.e(e) { "Error uploading trace for recording ${localRecording.id}" }
                            }
                        }
                    } catch (e: Exception) {
                        logger.e(e) { "Error uploading recording ${localRecording.id} to Firestore" }
                    } finally {
                        uploadingIds.remove(localRecording.id)
                    }
                }
            }
        }.flowOn(Dispatchers.IO).catch {
            logger.e(it) { "Error in local recording upload observer" }
        }.launchIn(scope)
    }

    override suspend fun processTask(task: RecordingProcessingTask) {
        val taskData = task.task
        val stage = task.lastSuccessfulStage?.let { RecordingProcessingStage.fromJson(it) }
        val handle = TaskHandle(task.id, stage)
        when (taskData) {
            is ProcessingTask.AudioRecording -> handleRecording(handle, taskData)
            is ProcessingTask.LocalAudioRecording -> handleRecording(handle, taskData)
            is ProcessingTask.TextRecording -> handleChat(handle, taskData)
        }
    }

    private suspend fun forcedNoteTool(messageText: String): ToolCallResult {
        val noteTool: CreateNoteTool = get()
        return noteTool.call(
            JsonSnake.encodeToString(
                JsonSnake.encodeToJsonElement(
                    CreateNoteTool.CreateNoteArgs(
                        text = messageText,
                        automatic = true
                    )
                ).jsonObject
            )
        )
    }

    private suspend fun handleRecording(
        handle: TaskHandle,
        recordingId: Long,
        fileId: String,
        transferId: Long?,
        buttonSequence: String?
    ) {
        try {
            trace.markEvent("recording_preprocessing_start", TraceEventData.TransferIdInfo(transferId ?: -1))
            recordingPreprocessor.preprocess(fileId)
            trace.markEvent("recording_preprocessing_end", TraceEventData.TransferIdInfo(transferId ?: -1))
        } catch (e: Exception) {
            logger.e(e) { "Preprocessing failed for file $fileId: ${e.message}, skipping preprocessing" }
        }
        val operation = recordingOperationFactory.createForButtonSequence(
            recordingId = recordingId,
            fileId = fileId,
            transferId = transferId,
            forcedNoteTool = ::forcedNoteTool,
            sequence = buttonSequence?.parseAsButtonSequence()
        )
        operation.run(handle)
    }

    private suspend fun handleRecording(handle: TaskHandle, task: ProcessingTask.LocalAudioRecording) {
        val (fileId, buttonSequence) = task
        logger.v { "Handling local recording $fileId" }
        val recordingId = if (handle.stage is RecordingProcessingStage.RecordingEntityCreated) {
            (handle.stage as RecordingProcessingStage.RecordingEntityCreated).recordingEntityId
        } else {
            val id = recordingRepository.createRecording()
            handle.updateStage(
                RecordingProcessingStage.RecordingEntityCreated(id)
            )
            id
        }
        handleRecording(
            handle,
            recordingId,
            fileId = fileId,
            transferId = null,
            buttonSequence = buttonSequence
        )
    }

    private suspend fun handleRecording(handle: TaskHandle, task: ProcessingTask.AudioRecording) {
        val (buttonSequence, transferId) = task
        logger.v { "Handling transfer $transferId" }
        trace.markEvent("handling_audio_task_start", TraceEventData.HandlingAudioTask(transferId))
        val transfer = transferRepository.getRingTransferById(transferId)
            ?: throw IllegalStateException("Transfer $transferId not found")
        val fileId = transfer.fileId
            ?: throw IllegalStateException("Transfer $transferId has no associated fileId")
        val recordingId = if (handle.stage is RecordingProcessingStage.RecordingEntityCreated) {
            val res = (handle.stage as RecordingProcessingStage.RecordingEntityCreated).recordingEntityId
            trace.markEvent("recording_entity_reused", TraceEventData.RecordingEntityCreated(
                recordingId = res,
                transferId = transferId
            ))
            res
        } else {
            val id = recordingRepository.createRecording(
                localTimestamp = transfer.transferInfo?.buttonPressed?.let { Instant.fromEpochMilliseconds(it) } ?: task.created
            )
            queueTaskRepository.updateTaskRecordingId(
                taskId = handle.taskId,
                recordingId = id
            )
            trace.markEvent("recording_entity_created", TraceEventData.RecordingEntityCreated(
                recordingId = id,
                transferId = transferId
            ))
            handle.updateStage(
                RecordingProcessingStage.RecordingEntityCreated(id)
            )
            id
        }
        transferRepository.linkRecordingToTransfer(
            transferId = transferId,
            recordingId = recordingId
        )
        handleRecording(
            handle = handle,
            recordingId = recordingId,
            fileId = fileId,
            transferId = transferId,
            buttonSequence = buttonSequence
        )
        trace.markEvent("handling_audio_task_end", TraceEventData.HandlingAudioTask(transferId))
    }

    private suspend fun handleChat(
        handle: TaskHandle,
        task: ProcessingTask.TextRecording
    ) {
        val (transcription) = task
        logger.v { "Handling text recording" }
        val recordingId = if (handle.stage is RecordingProcessingStage.RecordingEntityCreated) {
            (handle.stage as RecordingProcessingStage.RecordingEntityCreated).recordingEntityId
        } else {
            val id = recordingRepository.createRecording()
            handle.updateStage(
                RecordingProcessingStage.RecordingEntityCreated(id)
            )
            id
        }
        val operation = recordingOperationFactory.createTextOnlyOperation(
            recordingId = recordingId,
            text = transcription,
            forcedTool = { forcedNoteTool(transcription) }
        )
        operation.run(handle)
    }

    private suspend fun scheduleTask(task: RecordingProcessingTask): Long {
        val id = withContext(Dispatchers.IO) {
            queueTaskRepository.insertTask(task)
        }
        super.scheduleTask(id)
        return id
    }

    /**
     * Queues an audio processing task.
     * @return A deferred that completes with the created recording entry ID, or null if none was created/failure.
     */
    suspend fun queueAudioProcessing(
        transferId: Long,
        buttonSequence: String?,
    ) {
        val task = ProcessingTask.AudioRecording(
            transferId = transferId,
            buttonSequence = buttonSequence,
        )
        trace.markEvent("scheduling_audio_task",
            TraceEventData.SchedulingAudioTask(transferId, buttonSequence)
        )
        scheduleTask(
            RecordingProcessingTask(
                task = task
            )
        )
    }

    /**
     * Queues an audio processing task.
     * @return A deferred that completes with the created recording entry ID, or null if none was created/failure.
     */
    suspend fun queueLocalAudioProcessing(
        fileId: String,
        buttonSequence: String? = null,
    ) {
        val task = ProcessingTask.LocalAudioRecording(
            fileId = fileId,
            buttonSequence = buttonSequence,
        )
        trace.markEvent("scheduling_local_audio_task")
        scheduleTask(
            RecordingProcessingTask(
                task = task
            )
        )
    }

    suspend fun queueTextProcessing(
        transcription: String
    ) {
        val task = ProcessingTask.TextRecording(
            transcription = transcription
        )
        trace.markEvent("scheduling_text_task")
        scheduleTask(
            RecordingProcessingTask(
                task = task
            )
        )
    }

    suspend fun retryRecording(
        transferId: Long,
        buttonSequence: String?,
        recordingId: Long,
        recordingEntryId: Long,
    ) {
        val stage = RecordingProcessingStage.RecordingEntryCreated(
            recordingEntryId = recordingEntryId,
            recordingEntityId = recordingId,
        )
        val task = ProcessingTask.AudioRecording(
            transferId = transferId,
            buttonSequence = buttonSequence,
        )
        scheduleTask(
            RecordingProcessingTask(
                task = task,
                lastSuccessfulStage = stage.toJson(),
            )
        )
    }

    suspend fun retryLocalRecording(
        fileId: String,
        buttonSequence: String?,
        recordingId: Long,
        recordingEntryId: Long,
    ) {
        val stage = RecordingProcessingStage.RecordingEntryCreated(
            recordingEntryId = recordingEntryId,
            recordingEntityId = recordingId,
        )
        val task = ProcessingTask.LocalAudioRecording(
            fileId = fileId,
            buttonSequence = buttonSequence,
        )
        scheduleTask(
            RecordingProcessingTask(
                task = task,
                lastSuccessfulStage = stage.toJson(),
            )
        )
    }

    inner class TaskHandle(val taskId: Long, initialStage: RecordingProcessingStage?) {
        val stage: RecordingProcessingStage? get() = _stage
        private var _stage: RecordingProcessingStage? = initialStage
        suspend fun updateStage(newStage: RecordingProcessingStage) {
            _stage = newStage
            val stageString = newStage.toJson()
            withContext(Dispatchers.IO) {
                queueTaskRepository.updateLastSuccessfulStage(taskId, stageString)
            }
        }
    }
}

@Serializable
sealed interface RecordingProcessingStageJson {
    @Serializable
    data class RecordingEntityCreated(val recordingEntityId: Long): RecordingProcessingStageJson
    @Serializable
    data class RecordingEntryCreated(val recordingEntryId: Long, val recordingEntityId: Long): RecordingProcessingStageJson
}

fun RecordingProcessingStage.toJson(): String {
    return Json.encodeToString(
        // Remember this will return first matching type, so subsequent types should be earlier
        when (this) {
            is RecordingProcessingStage.RecordingEntryCreated -> RecordingProcessingStageJson.RecordingEntryCreated(
                recordingEntryId = this.recordingEntryId,
                recordingEntityId = this.recordingEntityId
            )
            is RecordingProcessingStage.RecordingEntityCreated -> RecordingProcessingStageJson.RecordingEntityCreated(
                recordingEntityId = this.recordingEntityId
            )
        }
    )
}

sealed interface RecordingProcessingStage {
    open class RecordingEntityCreated(val recordingEntityId: Long) : RecordingProcessingStage
    open class RecordingEntryCreated : RecordingEntityCreated {
        val recordingEntryId: Long
        constructor(recordingEntryId: Long, previous: RecordingEntityCreated): super(previous.recordingEntityId) {
            this.recordingEntryId = recordingEntryId
        }
        constructor(recordingEntryId: Long, recordingEntityId: Long): super(recordingEntityId) {
            this.recordingEntryId = recordingEntryId
        }
    }

    companion object {
        fun fromJson(json: String): RecordingProcessingStage {
            val jsonElement = Json.decodeFromString<RecordingProcessingStageJson>(json)
            return when (jsonElement) {
                is RecordingProcessingStageJson.RecordingEntityCreated -> RecordingEntityCreated(
                    recordingEntityId = jsonElement.recordingEntityId
                )
                is RecordingProcessingStageJson.RecordingEntryCreated -> RecordingEntryCreated(
                    recordingEntryId = jsonElement.recordingEntryId,
                    recordingEntityId = jsonElement.recordingEntityId
                )
            }
        }
    }
}