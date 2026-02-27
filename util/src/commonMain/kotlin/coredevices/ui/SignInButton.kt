package coredevices.ui

import PlatformUiContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import co.touchlab.kermit.Logger
import coredevices.analytics.AnalyticsBackend
import coredevices.analytics.setUser
import coredevices.util.auth.AppleAuthUtil
import coredevices.util.auth.GitHubAuthUtil
import coredevices.util.auth.GoogleAuthUtil
import coredevices.util.emailOrNull
import coredevices.util.rememberUiContext
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.AuthCredential
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.launch
import org.koin.compose.currentKoinScope
import org.koin.compose.koinInject

internal expect suspend fun signInWithCredential(credential: AuthCredential)

@Composable
fun SignInButton(
    onError: (String) -> Unit = {},
    onSuccess: () -> Unit = {},
    text: @Composable () -> Unit,
    credentialProvider: suspend (context: PlatformUiContext) -> AuthCredential?,
    enabled: Boolean = true,
) {
    val analyticsBackend: AnalyticsBackend = koinInject()
    val context = rememberUiContext()
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            scope.launch {
                val credential = try {
                    credentialProvider(context!!) ?: return@launch
                } catch (e: Exception) {
                    onError(e.message ?: "Unknown error")
                    return@launch
                }
                try {
                    signInWithCredential(credential)
                    Firebase.auth.currentUser?.emailOrNull?.let {
                        analyticsBackend.setUser(email = it)
                    }
                    Logger.i { "Signed in successfully as ${Firebase.auth.currentUser?.uid} via ${credential.providerId}" }
                    analyticsBackend.logEvent("signed_in_google", mapOf("provider" to credential.providerId))
                    onSuccess()
                } catch (e: Exception) {
                    Logger.e(e) { "Error signing in with credential: ${e.message}" }
                    onError("Network error during sign in")
                    return@launch
                }
            }
        },
        enabled = enabled
    ) {
        text()
    }
}

@Composable
fun SignInDialog(onDismiss: () -> Unit = {}) {
    val koin = currentKoinScope()
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onDismiss
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Sign in", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp))
                SignInButton(
                    onError = {
                        error = it
                    },
                    onSuccess = {
                        onDismiss()
                    },
                    text = { Text("Sign in with Google") },
                    credentialProvider = { context ->
                        val googleAuthUtil = koin.get<GoogleAuthUtil>()
                        googleAuthUtil.signInGoogle(context)
                    },
                )
                SignInButton(
                    onError = {
                        error = it
                    },
                    onSuccess = {
                        onDismiss()
                    },
                    text = { Text("Sign in with Apple") },
                    credentialProvider = { context ->
                        val appleAuthUtil = koin.get<AppleAuthUtil>()
                        appleAuthUtil.signInApple(context)
                    },
                )
                SignInButton(
                    onError = {
                        error = it
                    },
                    onSuccess = {
                        onDismiss()
                    },
                    text = { Text("Sign in with GitHub") },
                    credentialProvider = { context ->
                        val githubAuthUtil = koin.get<GitHubAuthUtil>()
                        githubAuthUtil.signInGithub(context)
                    },
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 16.dp))
                }
            }
        }
    }
}