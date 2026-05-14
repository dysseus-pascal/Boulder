package coredevices.ui

import co.touchlab.kermit.Logger
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.AuthCredential
import dev.gitlive.firebase.auth.FirebaseAuthUserCollisionException
import dev.gitlive.firebase.auth.auth

private val logger = Logger.withTag("SignInButton.ios")

internal actual suspend fun signInWithCredential(credential: AuthCredential) {
    val currentUser = Firebase.auth.currentUser
    if (currentUser?.isAnonymous == true) {
        try {
            currentUser.linkWithCredential(credential)
            logger.i { "Successfully linked anonymous user to account" }
            return
        } catch (_: FirebaseAuthUserCollisionException) {
            logger.i { "User is already created, not linking anonymous user" }
            throw AccountSwitchRequiredException(credential)
        }
    }
    Firebase.auth.signInWithCredential(credential)
}