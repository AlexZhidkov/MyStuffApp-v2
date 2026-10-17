package com.azhidkov.mystuff

data class AppConnection(
    val id: String,
    val clientName: String,
    val householdId: String,
    val householdName: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val revokedAt: Long? = null,
)

interface AppConnectionsGateway {
    fun load(onResult: (Result<List<AppConnection>>) -> Unit)
    fun revoke(connectionId: String, onResult: (Result<Unit>) -> Unit)
}

data class AppConnectionsUiState(
    val connections: List<AppConnection> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val revokingId: String? = null,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
)

class AppConnectionsController(private val gateway: AppConnectionsGateway) {
    var state = AppConnectionsUiState()
        private set
    var onStateChanged: (AppConnectionsUiState) -> Unit = {}

    fun load() {
        if (state.loading || state.revokingId != null) return
        update(state.copy(loading = true, errorMessage = null, noticeMessage = null))
        gateway.load { result ->
            result.onSuccess { connections ->
                update(state.copy(connections = connections, loading = false, loaded = true))
            }.onFailure {
                update(state.copy(loading = false, errorMessage = "Couldn’t load Connected apps. Try again."))
            }
        }
    }

    fun revoke(connectionId: String) {
        if (state.loading || state.revokingId != null ||
            state.connections.none { it.id == connectionId && it.revokedAt == null }
        ) return
        update(state.copy(revokingId = connectionId, errorMessage = null, noticeMessage = null))
        gateway.revoke(connectionId) { result ->
            result.onSuccess {
                update(state.copy(
                    connections = state.connections.filterNot { it.id == connectionId },
                    revokingId = null,
                    noticeMessage = "App Connection revoked.",
                ))
            }.onFailure {
                update(state.copy(revokingId = null, errorMessage = "Couldn’t revoke this App Connection. Try again."))
            }
        }
    }

    private fun update(value: AppConnectionsUiState) {
        state = value
        onStateChanged(value)
    }
}
