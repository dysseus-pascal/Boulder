package coredevices.mcp

import kotlin.time.Instant

/**
 * Context for the session a tool call belongs to.
 * @property timeBase the true time of the recording being processed (not upload/processing time),
 * to be used as the base for e.g. relative time calculations.
 */
data class SessionContext(
    val timeBase: Instant,
)
