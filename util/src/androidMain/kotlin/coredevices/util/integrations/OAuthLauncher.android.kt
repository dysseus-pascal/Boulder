package coredevices.util.integrations

import com.eygraber.uri.Uri
import coredevices.util.OAuthRedirectHandler
import coredevices.util.Platform
import kotlinx.coroutines.flow.first

/**
 * Android opens the auth URL in the browser and receives the redirect back via the app's
 * `pebble://oauth/...` deep link, routed through [OAuthRedirectHandler].
 */
class AndroidOAuthLauncher(
    private val platform: Platform,
    private val oAuthRedirectHandler: OAuthRedirectHandler,
) : OAuthLauncher {
    override suspend fun authenticate(
        authUrl: String,
        callbackScheme: String,
        expectedPathSegment: String,
    ): Uri {
        platform.openUrl(authUrl)
        return oAuthRedirectHandler.oauthRedirects.first {
            it.host == "oauth" && it.lastPathSegment == expectedPathSegment
        }
    }
}
