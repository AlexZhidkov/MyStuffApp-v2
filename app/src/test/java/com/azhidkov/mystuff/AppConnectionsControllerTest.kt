package com.azhidkov.mystuff

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConnectionsControllerTest {
    @Test
    fun `failed revocation preserves access and retry revokes only the selected connection`() {
        val gateway = DeferredAppConnectionsGateway()
        val controller = AppConnectionsController(gateway)
        val first = AppConnection("one", "Gemini CLI", "home", "Our Home", 1000, 2000)
        val second = first.copy(id = "two")
        controller.load()
        gateway.loaded(Result.success(listOf(first, second)))
        controller.revoke("one")
        assertEquals("one", controller.state.revokingId)
        assertEquals(listOf(first, second), controller.state.connections)
        gateway.revoked(Result.failure(IllegalStateException("Offline")))
        assertEquals(listOf(first, second), controller.state.connections)
        assertEquals(null, controller.state.noticeMessage)
        assertEquals("Couldn’t revoke this App Connection. Try again.", controller.state.errorMessage)
        controller.revoke("one")
        gateway.revoked(Result.success(Unit))
        assertEquals(listOf(second), controller.state.connections)
        assertEquals("App Connection revoked.", controller.state.noticeMessage)
        assertEquals(null, controller.state.errorMessage)
        assertEquals(null, controller.state.revokingId)
    }

    @Test
    fun `loading and failed listing never look like an empty successful list and can retry`() {
        val gateway = DeferredAppConnectionsGateway()
        val controller = AppConnectionsController(gateway)
        controller.load()
        assertEquals(true, controller.state.loading)
        assertEquals(false, controller.state.loaded)
        gateway.loaded(Result.failure(IllegalStateException("Offline")))
        assertEquals(false, controller.state.loading)
        assertEquals(false, controller.state.loaded)
        assertEquals("Couldn’t load Connected apps. Try again.", controller.state.errorMessage)
        controller.load()
        gateway.loaded(Result.success(emptyList()))
        assertEquals(true, controller.state.loaded)
        assertEquals(emptyList<AppConnection>(), controller.state.connections)
        assertEquals(null, controller.state.errorMessage)
    }
}

private class DeferredAppConnectionsGateway : AppConnectionsGateway {
    lateinit var loaded: (Result<List<AppConnection>>) -> Unit
    lateinit var revoked: (Result<Unit>) -> Unit
    var revokingId: String? = null
    override fun load(onResult: (Result<List<AppConnection>>) -> Unit) { loaded = onResult }
    override fun revoke(connectionId: String, onResult: (Result<Unit>) -> Unit) {
        revokingId = connectionId
        revoked = onResult
    }
}
