package coredevices.ring.agent

import co.touchlab.kermit.Logger
import com.cactus.cactusComplete
import com.cactus.cactusInit
import coredevices.indexai.agent.Agent
import coredevices.indexai.data.entity.ConversationMessageDocument
import coredevices.indexai.data.entity.FunctionToolCall
import coredevices.indexai.data.entity.MessageRole
import coredevices.indexai.data.entity.ToolCall
import coredevices.mcp.client.McpSession
import coredevices.mcp.data.SemanticResult
import coredevices.ring.model.CactusModelProvider
import coredevices.ring.transcription.InferenceBoostProvider
import coredevices.util.CoreConfigFlow
import coredevices.ring.transcription.NoOpInferenceBoostProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.time.Clock

class AgentCactus(
    private val modelProvider: CactusModelProvider,
    conversation: List<ConversationMessageDocument>,
    private val inferenceBoost: InferenceBoostProvider = NoOpInferenceBoostProvider()
) : KoinComponent, Agent {
    override val label = "Cactus"
    private var _conversation = MutableSharedFlow<List<ConversationMessageDocument>>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
        tryEmit(conversation)
    }
    override val conversation: SharedFlow<List<ConversationMessageDocument>> get() = _conversation

    companion object {
        private val logger = Logger.withTag(AgentCactus::class.simpleName!!)
    }

    private val agentMutex = Mutex()
    private var modelHandle: Long = 0L

    private suspend fun initializeIfNeeded() {
        if (modelHandle == 0L) {
            logger.d { "Initializing CactusAgent for the first time..." }
            val initStart = Clock.System.now()
            val modelPath = modelProvider.getLMModelPath()
            modelHandle = cactusInit(modelPath, null, false)
            val initDuration = Clock.System.now() - initStart
            logger.i { "CactusAgent model initialized: $modelPath in $initDuration" }
        }
    }

    override suspend fun send(
        input: String,
        mcpSession: McpSession,
        includePromptsFromMcps: Map<String, Set<String>>,
        skipToolExecution: Boolean
    ) {
        logger.i { "CactusAgent received input: ${if (get<CoreConfigFlow>().value.obfuscateSensitiveLogs) "[${input.length} chars redacted]" else input}" }

        agentMutex.withLock {
            initializeIfNeeded()
            val handle = modelHandle
            if (handle == 0L) throw IllegalStateException("CactusAgent model not initialized")

            // Convert MCP tool definitions to JSON for cactusComplete.
            // Use SHORT tool names (e.g. "create_note") not composite names
            // (e.g. "builtin_note.create_note") because Needle's constrained
            // decoding grammar is built from these names and the model was
            // trained on short names only.
            val mcpTools = mcpSession.listTools()
            val toolParentMap = mutableMapOf<String, String>()
            val toolsJson = buildJsonArray {
                mcpTools.forEach { (parentName, tool) ->
                    val definition = tool.definition
                    val required = definition.inputSchema.required ?: emptyList()
                    toolParentMap[definition.name] = parentName
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", definition.name)
                            put("description", definition.description ?: "")
                            put("parameters", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    definition.inputSchema.properties?.forEach { (propName, param) ->
                                        put(propName, buildJsonObject {
                                            put("type", param.jsonObject["type"]?.jsonPrimitive?.content ?: "string")
                                            param.jsonObject["description"]?.jsonPrimitive?.content?.let {
                                                put("description", it)
                                            }
                                        })
                                    }
                                })
                                put("required", buildJsonArray {
                                    required.forEach { add(JsonPrimitive(it)) }
                                })
                            })
                        })
                    })
                }
            }.toString()

            mcpTools.forEach { (parentName, tool) ->
                logger.i { "CactusAgent tool available: $parentName.${tool.definition.name}" }
            }

            // Needle is an encoder-decoder model, not a chat model.
            // It encodes [query <tools> tools_json] directly — no system prompt,
            // no /no_think prefix.
            val messagesJson = buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", input)
                })
            }.toString()

            val optionsJson = buildJsonObject {
                put("max_tokens", 256)
                put("temperature", 0.0)
                put("tool_rag_top_k", 0)
            }.toString()

            _conversation.emit(_conversation.first() + ConversationMessageDocument(
                role = MessageRole.user,
                content = input
            ))

            inferenceBoost.acquire()
            val resultJson = try {
                cactusComplete(handle, messagesJson, optionsJson, toolsJson, null)
            } finally {
                inferenceBoost.release()
            }

            // Parse the JSON result
            val resultObj = Json.parseToJsonElement(resultJson).jsonObject
            val resultText = resultObj["response"]?.jsonPrimitive?.content ?: ""
            val functionCalls = resultObj["function_calls"]?.jsonArray

            // Parse function calls — model returns SHORT names, map back to composite
            val toolCalls = functionCalls?.mapNotNull { callElement ->
                val call = callElement.jsonObject
                val shortName = call["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val arguments = call["arguments"]?.jsonObject
                val parent = toolParentMap[shortName]
                if (parent == null) {
                    logger.w { "Unknown tool name from model: $shortName" }
                    return@mapNotNull null
                }
                Triple("$parent.$shortName", arguments, call)
            } ?: emptyList()

            _conversation.emit(_conversation.first() + ConversationMessageDocument(
                role = MessageRole.assistant,
                content = resultText.ifBlank { null },
                tool_calls = toolCalls.map { (name, args, _) ->
                    ToolCall(
                        id = name,
                        type = "function",
                        function = FunctionToolCall(
                            name = name,
                            arguments = args.toString()
                        )
                    )
                },
                language_model_used = "cactus-${modelProvider.getLMModelPath().substringAfterLast("/")}"
            ))

            // If no tool was called, save the utterance as a note (fallback)
            if (toolCalls.isEmpty() && !skipToolExecution) {
                val noteResult = mcpSession.callTool(
                    integrationName = "builtin_note",
                    toolName = "create_note",
                    jsonInput = buildJsonObject {
                        put("text", JsonPrimitive(input))
                    },
                    requireExists = false
                )
                _conversation.emit(
                    _conversation.first().toMutableList().apply {
                        add(ConversationMessageDocument(
                            role = MessageRole.tool,
                            tool_call_id = "fallback_note",
                            content = noteResult.resultString,
                            semantic_result = noteResult.semanticResult
                        ))
                    }
                )
            }

            if (toolCalls.isNotEmpty() && !skipToolExecution) {
                for ((name, arguments, _) in toolCalls) {
                    val (parent, toolName) = name.split(".", limit = 2)
                    val jsonInput = if (arguments != null) {
                        buildJsonObject {
                            arguments.forEach { (k, v) ->
                                put(k, v)
                            }
                        }
                    } else {
                        buildJsonObject {}
                    }
                    val toolResult = mcpSession.callTool(
                        integrationName = parent,
                        toolName = toolName,
                        jsonInput = jsonInput,
                        requireExists = false
                    )
                    _conversation.emit(
                        _conversation.first().toMutableList().apply {
                            add(ConversationMessageDocument(
                                role = MessageRole.tool,
                                tool_call_id = name,
                                content = toolResult.resultString,
                                semantic_result = toolResult.semanticResult
                            ))
                        }
                    )
                }
            }
        }
    }

    override suspend fun addMessage(message: ConversationMessageDocument) {
        _conversation.emit(_conversation.first() + message)
    }
}
