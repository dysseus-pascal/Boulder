package io.rebble.libpebblecommon.util

import io.rebble.libpebblecommon.NotificationConfigFlow

class PrivateLogger(private val notificationConfig: NotificationConfigFlow) {
    fun obfuscate(content: CharSequence?): String? {
        if (content == null) return null
        if (notificationConfig.value.obfuscateContent) {
            return content.lines().joinToString("\n") { hashString(it) }
        }
        return content.toString()
    }

    companion object {
        private fun hashString(input: String): String {
            return input.hashCode().toString()
        }
    }
}

fun CharSequence?.obfuscate(logger: PrivateLogger) = logger.obfuscate(this)