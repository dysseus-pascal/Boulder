package coredevices.util.integrations

import com.eygraber.uri.Uri

/**
 * Launches an OAuth authorization flow and suspends until the provider redirects back to
 * our app's custom-scheme callback, returning the redirect [Uri].
 *
 * On iOS this is backed by `ASWebAuthenticationSession` so the authorization page always
 * renders in a secure in-app web context. Opening the provider's `https` auth URL directly
 * (the previous behaviour) lets iOS universal links hand it to an installed provider app
 * (e.g. the Notion app), which opens silently and never redirects back — leaving the sign-in
 * coroutine suspended forever. The web auth session also reports user cancellation, so the
 * flow can never hang.
 */
interface OAuthLauncher {
    /**
     * @param authUrl the provider authorization URL to open.
     * @param callbackScheme the custom URL scheme our app registers for the redirect (e.g. "pebble").
     * @param expectedPathSegment the last path segment identifying this integration's redirect.
     * @throws OAuthCancelledException if the user cancels the flow.
     */
    suspend fun authenticate(
        authUrl: String,
        callbackScheme: String,
        expectedPathSegment: String,
    ): Uri
}

/** Thrown when the user cancels the OAuth flow (e.g. dismisses the web auth sheet). */
class OAuthCancelledException : Exception("OAuth sign in was cancelled")
