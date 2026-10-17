package com.azhidkov.mystuff

import com.google.firebase.functions.FirebaseFunctions

class FirebaseAppConnectionsGateway(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("australia-southeast1"),
) : AppConnectionsGateway {
    override fun load(onResult: (Result<List<AppConnection>>) -> Unit) {
        functions.getHttpsCallable("listAppConnections").call().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                onResult(Result.failure(task.exception ?: IllegalStateException("Connected apps could not be loaded.")))
                return@addOnCompleteListener
            }
            onResult(runCatching {
                val response = task.result?.data as? Map<*, *> ?: error("Invalid Connected apps response.")
                val connections = response["connections"] as? List<*> ?: error("Invalid Connected apps response.")
                connections.map { value ->
                    val connection = value as? Map<*, *> ?: error("Invalid App Connection.")
                    AppConnection(
                        id = connection.requiredString("id"),
                        clientName = connection.requiredString("clientName"),
                        householdId = connection.requiredString("householdId"),
                        householdName = connection.requiredString("householdName"),
                        createdAt = connection.requiredTime("createdAt"),
                        lastUsedAt = connection.requiredTime("lastUsedAt"),
                        revokedAt = if (connection["revokedAt"] == null) null else connection.requiredTime("revokedAt"),
                    )
                }
            })
        }
    }

    override fun revoke(connectionId: String, onResult: (Result<Unit>) -> Unit) {
        functions.getHttpsCallable("revokeAppConnection")
            .call(mapOf("connectionId" to connectionId))
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    onResult(Result.failure(task.exception ?: IllegalStateException("App Connection could not be revoked.")))
                    return@addOnCompleteListener
                }
                onResult(runCatching {
                    val response = task.result?.data as? Map<*, *>
                    check(response?.get("revoked") == true) { "Invalid revocation response." }
                })
            }
    }
}

private fun Map<*, *>.requiredString(key: String): String =
    this[key] as? String ?: error("Invalid App Connection.")

private fun Map<*, *>.requiredTime(key: String): Long =
    (this[key] as? Number)?.toLong() ?: error("Invalid App Connection.")
