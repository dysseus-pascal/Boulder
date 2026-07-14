package coredevices.util.usage

import co.touchlab.kermit.Logger
import coredevices.database.CactusUsageEventEntity
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface CactusUsageUploader {
    suspend fun upload(events: List<CactusUsageEventEntity>): Boolean
}

class SupabaseCactusUsageUploader(
    private val httpClient: HttpClient,
    private val supabaseUrl: String?,
    private val supabaseKey: String?,
) : CactusUsageUploader {
    private val logger = Logger.withTag("CactusUsageUploader")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    override suspend fun upload(events: List<CactusUsageEventEntity>): Boolean {
        if (events.isEmpty()) return true
        val url = supabaseUrl
        val key = supabaseKey
        if (url.isNullOrBlank() || key.isNullOrBlank()) {
            logger.w { "Supabase usage credentials not configured; dropping ${events.size} events" }
            return true
        }
        return try {
            val eventsJson = json.encodeToString(ListSerializer(CactusUsageEventEntity.serializer()), events)
            val body = """{"events":$eventsJson}"""
            val response = httpClient.post("$url/rest/v1/rpc/cactus_pebble_usage_insert") {
                header("apikey", key)
                header("Authorization", "Bearer $key")
                header("Content-Type", "application/json")
                setBody(body)
            }
            if (response.status.isSuccess()) {
                logger.d { "Uploaded ${events.size} cactus_pebble_usage" }
                true
            } else {
                val errBody = response.bodyAsText()
                logger.w { "Upload failed: status=${response.status} body=$errBody" }
                false
            }
        } catch (e: Exception) {
            logger.w(e) { "Upload threw: ${e.message}" }
            false
        }
    }
}
