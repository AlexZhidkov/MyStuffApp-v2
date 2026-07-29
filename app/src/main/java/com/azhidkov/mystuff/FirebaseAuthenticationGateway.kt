package com.azhidkov.mystuff

import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

class FirebaseAuthenticationGateway(
    private val activity: ComponentActivity,
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val credentialManager: CredentialManager = CredentialManager.create(activity),
) : AuthenticationGateway {

    override val currentIdentity: AuthenticatedIdentity?
        get() {
            val firebaseUser = firebaseAuth.currentUser ?: return null
            if (!firebaseUser.hasGoogleProvider()) {
                firebaseAuth.signOut()
                return null
            }
            return firebaseUser.toAuthenticatedIdentity()
        }

    override fun signIn(onResult: (Result<AuthenticatedIdentity>) -> Unit) {
        activity.lifecycleScope.launch {
            val credential = try {
                credentialManager.getCredential(
                    context = activity,
                    request = googleCredentialRequest(),
                ).credential
            } catch (_: GetCredentialCancellationException) {
                onResult(
                    Result.failure(
                        GoogleAuthenticationException(
                            "Google sign-in was cancelled. You can try again.",
                        ),
                    ),
                )
                return@launch
            } catch (_: NoCredentialException) {
                onResult(
                    Result.failure(
                        GoogleAuthenticationException(
                            "No Google Account is available on this device. Add one and try again.",
                        ),
                    ),
                )
                return@launch
            } catch (_: GetCredentialException) {
                onResult(
                    Result.failure(
                        GoogleAuthenticationException(
                            "Google sign-in couldn't start. Check your connection and try again.",
                        ),
                    ),
                )
                return@launch
            }

            val idToken = runCatching { credential.googleIdToken() }
                .getOrElse {
                    onResult(
                        Result.failure(
                            GoogleAuthenticationException(
                                "Google returned an unsupported credential. Please try again.",
                            ),
                        ),
                    )
                    return@launch
                }

            val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
            firebaseAuth.signInWithCredential(firebaseCredential)
                .addOnCompleteListener(activity) { task ->
                    val identity = firebaseAuth.currentUser
                    if (task.isSuccessful && identity != null) {
                        onResult(Result.success(identity.toAuthenticatedIdentity()))
                    } else {
                        onResult(
                            Result.failure(
                                GoogleAuthenticationException(
                                    "Google couldn't verify this account. Check your connection and try again.",
                                ),
                            ),
                        )
                    }
                }
        }
    }

    override fun signOut(onResult: (Result<Unit>) -> Unit) {
        runCatching(firebaseAuth::signOut)
            .onFailure {
                onResult(Result.failure(it))
                return
            }

        activity.lifecycleScope.launch {
            onResult(
                runCatching {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                },
            )
        }
    }

    private fun googleCredentialRequest(): GetCredentialRequest {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(activity.getString(R.string.default_web_client_id))
            .setAutoSelectEnabled(true)
            .build()

        return GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
    }
}

private class GoogleAuthenticationException(message: String) : Exception(message)

private fun Credential.googleIdToken(): String {
    if (
        this is CustomCredential &&
        type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        return GoogleIdTokenCredential.createFrom(data).idToken
    }
    throw IllegalArgumentException("Credential is not a Google ID token")
}

private fun FirebaseUser.hasGoogleProvider(): Boolean =
    providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }

private fun FirebaseUser.toAuthenticatedIdentity(): AuthenticatedIdentity = AuthenticatedIdentity(
    id = uid,
    displayName = displayName,
    email = email,
)
