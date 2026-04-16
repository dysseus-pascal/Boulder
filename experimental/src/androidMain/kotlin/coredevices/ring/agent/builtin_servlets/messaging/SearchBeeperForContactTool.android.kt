package coredevices.ring.agent.builtin_servlets.messaging

import co.touchlab.kermit.Logger
import coredevices.indexai.util.JsonSnake
import coredevices.mcp.BuiltInMcpTool
import coredevices.mcp.data.SemanticResult
import coredevices.mcp.data.ToolCallResult
import coredevices.ring.agent.builtin_servlets.messaging.SearchBeeperForContactToolConstants.EXTRA_CONTEXT
import coredevices.ring.agent.builtin_servlets.messaging.SearchBeeperForContactToolConstants.INPUT_SCHEMA
import coredevices.ring.agent.builtin_servlets.messaging.SearchBeeperForContactToolConstants.TOOL_DESCRIPTION
import coredevices.ring.agent.builtin_servlets.messaging.SearchBeeperForContactToolConstants.TOOL_NAME
import coredevices.ring.database.Preferences
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

actual class SearchBeeperForContactTool : BuiltInMcpTool(
    definition = Tool(
        name = TOOL_NAME,
        description = TOOL_DESCRIPTION,
        inputSchema = INPUT_SCHEMA
    ),
    extraContext = EXTRA_CONTEXT
), KoinComponent {
    private val prefs: Preferences by inject()

    companion object {
        private val logger = Logger.withTag("SearchBeeperForContactTool")
    }

    actual override suspend fun call(jsonInput: String): ToolCallResult {
        val (name) = JsonSnake.decodeFromString<SearchBeeperForContactArgs>(jsonInput)
        val approvedContacts = prefs.approvedBeeperContacts.value

        if (approvedContacts.isEmpty()) {
            return ToolCallResult(
                "No approved contacts configured",
                SemanticResult.GenericFailure("No contacts have been approved for messaging. Add contacts in Settings.")
            )
        }

        // Score all approved contacts against the query, keep matches
        val matches = approvedContacts
            .map { it to it.matchScore(name) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        if (matches.isEmpty()) {
            val allNames = approvedContacts.joinToString(", ") { it.nickname ?: it.name }
            return ToolCallResult(
                "No contacts matching '$name'. Approved contacts: $allNames",
                SemanticResult.GenericFailure("No approved contacts match '$name'")
            )
        }

        // Return the best match as a displayName -> roomId map
        // Sorted best-first, so first entry per name wins
        val resultMap = LinkedHashMap<String, String>()
        for ((contact, _) in matches) {
            val displayName = contact.nickname ?: contact.name
            if (!resultMap.containsKey(displayName)) {
                resultMap[displayName] = contact.roomId
            }
        }

        logger.d { "Contact search for '$name': ${matches.size} match(es), best=${matches.first().first.name} (score=${matches.first().second})" }

        return ToolCallResult(
            JsonSnake.encodeToString(resultMap),
            SemanticResult.SupportingData("Found ${resultMap.size} contact(s) matching '$name'")
        )
    }
}
