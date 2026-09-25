package coredevices.util.integrations

import com.eygraber.uri.Uri
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * iOS uses `ASWebAuthenticationSession`, which renders the authorization page in a secure
 * in-app web context and delivers the redirect directly to its completion handler. This
 * avoids iOS universal links routing the provider's `https` auth URL into an installed
 * provider app (which would open silently and never redirect back).
 */
class IosOAuthLauncher : OAuthLauncher {
    override suspend fun authenticate(
        authUrl: String,
        callbackScheme: String,
        expectedPathSegment: String,
    ): Uri = callbackFlow<Uri> {
        val contextProvider = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
            override fun presentationAnchorForWebAuthenticationSession(
                session: ASWebAuthenticationSession
            ): ASPresentationAnchor =
                UIApplication.sharedApplication.keyWindow
                    ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
                    ?: UIWindow()
        }
        val session = ASWebAuthenticationSession(
            NSURL.URLWithString(authUrl)!!,
            callbackScheme,
        ) { callbackURL: NSURL?, error: NSError? ->
            when {
                error != null -> {
                    // ASWebAuthenticationSessionErrorCodeCanceledLogin == 1: user dismissed the sheet.
                    if (error.code == 1L) {
                        close(OAuthCancelledException())
                    } else {
                        close(Exception(error.localizedDescription))
                    }
                }
                callbackURL != null -> trySend(Uri.parse(callbackURL.absoluteString.orEmpty()))
                else -> close(Exception("No callback URL returned"))
            }
        }
        session.presentationContextProvider = contextProvider
        session.start()
        // Keep session/contextProvider alive until the flow completes; cancel if collection ends.
        awaitClose { session.cancel() }
    }.first()
}
