package coredevices.util.usage

import com.russhwolf.settings.Settings
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface InstallIdProvider {
    val installId: String
}

@OptIn(ExperimentalUuidApi::class)
class SettingsInstallIdProvider(
    private val settings: Settings,
) : InstallIdProvider {
    override val installId: String by lazy {
        val existing = settings.getStringOrNull(KEY_INSTALL_ID)
        if (existing != null) {
            existing
        } else {
            val fresh = Uuid.random().toString()
            settings.putString(KEY_INSTALL_ID, fresh)
            fresh
        }
    }

    companion object {
        const val KEY_INSTALL_ID = "cactus_usage_install_id"
    }
}
