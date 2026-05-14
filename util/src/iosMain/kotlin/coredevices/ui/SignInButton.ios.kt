package coredevices.ui

import co.touchlab.kermit.Logger
import cocoapods.FirebaseAuth.FIRAuthDataResult
import cocoapods.FirebaseAuth.FIRAuthErrorUserInfoUpdatedCredentialKey
import cocoapods.FirebaseAuth.FIROAuthCredential
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.AuthCredential
import dev.gitlive.firebase.auth.OAuthCredential
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.auth.ios
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSError

private sealed interface LinkResult {
    data class Success(val authResult: FIRAuthDataResult?) : LinkResult
    data class Failure(val error: NSError) : LinkResult
}

private val logger = Logger.withTag("SignInButton.ios")

internal actual suspend fun signInWithCredential(credential: AuthCredential) {
    if (Firebase.auth.currentUser?.isAnonymous == true) {
        val deferred = CompletableDeferred<LinkResult>()
        Firebase.auth.ios.currentUser()?.linkWithCredential(credential.ios) { authResult, error ->
            if (error != null) {
                deferred.complete(LinkResult.Failure(error))
            } else {
                deferred.complete(LinkResult.Success(authResult))
            }
        }
        when (val result = deferred.await()) {
            is LinkResult.Failure -> {
                val userInfo = result.error.userInfo
                val updatedCredential = userInfo[FIRAuthErrorUserInfoUpdatedCredentialKey] as? FIROAuthCredential
                logger.i { "User is already created, not linking anonymous user" }
                throw AccountSwitchRequiredException(
                    updatedCredential?.let { OAuthCredential(it) } ?: credential
                )
            }
            is LinkResult.Success -> {
                logger.i { "Successfully linked anonymous user to account" }
                result.authResult?.credential()?.let {
                    Firebase.auth.signInWithCredential(OAuthCredential(it))
                } ?: throw IllegalStateException("Linking succeeded but no credential returned")
            }
        }
    } else {
        Firebase.auth.signInWithCredential(credential)
    }
}