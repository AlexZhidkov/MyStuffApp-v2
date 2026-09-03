package com.azhidkov.mystuff

import com.google.firebase.functions.FirebaseFunctions
import java.net.URI

fun interface InvitationAcceptanceGateway {
    fun accept(
        invitationId: String,
        onResult: (Result<String>) -> Unit,
    )
}

internal object NoInvitationAcceptanceGateway : InvitationAcceptanceGateway {
    override fun accept(
        invitationId: String,
        onResult: (Result<String>) -> Unit,
    ) {
        onResult(
            Result.failure(
                UnsupportedOperationException("Invitation acceptance is unavailable."),
            ),
        )
    }
}

class FirebaseInvitationAcceptanceGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTION_REGION),
) : InvitationAcceptanceGateway {
    override fun accept(
        invitationId: String,
        onResult: (Result<String>) -> Unit,
    ) {
        functions
            .getHttpsCallable(FUNCTION_NAME)
            .call(mapOf("invitationId" to invitationId))
            .addOnCompleteListener { task ->
                val result = if (task.isSuccessful) {
                    runCatching {
                        val response = task.result?.data as? Map<*, *>
                            ?: error("Invitation acceptance returned an invalid response.")
                        response["householdId"] as? String
                            ?: error("Invitation acceptance returned no Household.")
                    }
                } else {
                    Result.failure(
                        task.exception
                            ?: IllegalStateException("The invitation could not be accepted."),
                    )
                }
                onResult(result)
            }
    }
}

fun invitationIdFromLink(link: String?): String? {
    val uri = link?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (!uri.scheme.equals("mystuff", ignoreCase = true)) return null
    if (!uri.host.equals("invitation", ignoreCase = true)) return null
    val invitationId = uri.path.removePrefix("/")
    return invitationId.takeIf { it.isNotBlank() && '/' !in it }
}

private const val FUNCTION_REGION = "australia-southeast1"
private const val FUNCTION_NAME = "acceptHouseholdInvitation"
