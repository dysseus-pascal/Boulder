package coredevices.coreapp.auth

import PlatformUiContext
import co.touchlab.kermit.Logger
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.auth
import dev.gitlive.firebase.auth.AuthCredential
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.tasks.await

private val logger = Logger.withTag("OAuthProviderSignIn")

// startActivityForSignInWithProvider signs in as a side effect, so an anonymous user is already
// gone by the time the caller gets a credential to decide with. Anonymous users must link instead.
internal suspend fun oAuthProviderCredential(
    context: PlatformUiContext,
    providerId: String,
    scopes: List<String>,
): AuthCredential? {
    val builder = OAuthProvider.newBuilder(providerId)
    builder.scopes = scopes
    val provider = builder.build()
    val anonUser = Firebase.auth.currentUser?.takeIf { it.isAnonymous }

    val result = try {
        Firebase.auth.pendingAuthResult?.await()
            ?: anonUser?.startActivityForLinkWithProvider(context.activity, provider)?.await()
            ?: Firebase.auth.startActivityForSignInWithProvider(context.activity, provider).await()
    } catch (e: CancellationException) {
        logger.i("$providerId sign-in cancelled")
        return null
    } catch (e: FirebaseAuthUserCollisionException) {
        val credential = e.updatedCredential
            ?: throw IllegalStateException("$providerId sign-in failed", e)
        logger.i("$providerId account already exists, leaving anonymous user for caller to confirm")
        return AuthCredential(credential)
    } catch (e: Exception) {
        logger.e(e) { "$providerId sign-in failed" }
        throw IllegalStateException("$providerId sign-in failed", e)
    }

    if (anonUser != null) {
        logger.i("Linked anonymous user ${anonUser.uid.take(8)} to $providerId")
    }
    return result.credential?.let { AuthCredential(it) }
}
